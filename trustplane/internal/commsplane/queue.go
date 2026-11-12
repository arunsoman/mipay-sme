package commsplane

// Queue (comms-design.md §3.2): code depends only on this interface, so moving from the
// Postgres queue to NATS JetStream later is configuration. Phase 1 is Postgres with
// FOR UPDATE SKIP LOCKED; lanes are separate (CRITICAL workers never take BULK work).

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Job struct {
	ID         int64
	Stage      string // plan | dispatch
	Lane       string
	TenantID   string
	MessageID  string
	Attempts   int
	Payload    []byte // sealed
	LeaseUntil time.Time
}

type Queue interface {
	Enqueue(ctx context.Context, j Job) error
	// Lease takes up to n available jobs of one stage and lane for ttl.
	Lease(ctx context.Context, stage, lane string, n int, ttl time.Duration) ([]Job, error)
	Ack(ctx context.Context, j Job) error
	// Nack makes the job available again after delay (attempts already counted by Lease).
	Nack(ctx context.Context, j Job, delay time.Duration) error
	// Advance moves a job to the next stage with a new payload (planner -> dispatcher).
	Advance(ctx context.Context, j Job, stage string, payload []byte) error
	// DeadLetter parks a job for inspection and replay; the payload is wiped.
	DeadLetter(ctx context.Context, j Job, reason string) error
}

type pgQueue struct{ pool *pgxpool.Pool }

func (q pgQueue) Enqueue(ctx context.Context, j Job) error {
	_, err := q.pool.Exec(ctx, `INSERT INTO jobs (stage, lane, tenant_bucket, message_id, payload) VALUES ($1,$2,$3,$4,$5)`,
		j.Stage, j.Lane, j.TenantID, j.MessageID, j.Payload)
	return err
}

func (q pgQueue) Lease(ctx context.Context, stage, lane string, n int, ttl time.Duration) ([]Job, error) {
	rows, err := q.pool.Query(ctx, `
		WITH c AS (
			SELECT id FROM jobs
			WHERE stage=$1 AND lane=$2 AND NOT dead AND available_at <= now()
			  AND (lease_until IS NULL OR lease_until < now())
			ORDER BY available_at LIMIT $3 FOR UPDATE SKIP LOCKED)
		UPDATE jobs j SET lease_until = now() + make_interval(secs => $4), attempts = j.attempts + 1
		FROM c WHERE j.id = c.id
		RETURNING j.id, j.stage, j.lane, j.tenant_bucket, j.message_id, j.attempts, j.payload, j.lease_until`,
		stage, lane, n, ttl.Seconds())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Job
	for rows.Next() {
		var j Job
		if err := rows.Scan(&j.ID, &j.Stage, &j.Lane, &j.TenantID, &j.MessageID, &j.Attempts, &j.Payload, &j.LeaseUntil); err != nil {
			return nil, err
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

func (q pgQueue) Ack(ctx context.Context, j Job) error {
	_, err := q.pool.Exec(ctx, `DELETE FROM jobs WHERE id=$1`, j.ID)
	return err
}

func (q pgQueue) Nack(ctx context.Context, j Job, delay time.Duration) error {
	_, err := q.pool.Exec(ctx, `UPDATE jobs SET lease_until=NULL, available_at=now()+make_interval(secs => $2) WHERE id=$1`,
		j.ID, delay.Seconds())
	return err
}

func (q pgQueue) Advance(ctx context.Context, j Job, stage string, payload []byte) error {
	_, err := q.pool.Exec(ctx, `UPDATE jobs SET stage=$2, payload=$3, attempts=0, lease_until=NULL, available_at=now() WHERE id=$1`,
		j.ID, stage, payload)
	return err
}

func (q pgQueue) DeadLetter(ctx context.Context, j Job, reason string) error {
	_, err := q.pool.Exec(ctx, `UPDATE jobs SET dead=TRUE, dead_reason=$2, payload='\x'::bytea, lease_until=NULL WHERE id=$1`,
		j.ID, reason)
	return err
}

// relayOnce publishes unpublished outbox rows to the queue (comms-design §4: "Accepted =
// durable"). A crash between the two steps re-publishes; the planner is idempotent on the
// message status, so a duplicate job is dropped rather than sent twice.
func (s *Server) relayOnce(ctx context.Context) (int, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id, message_id, lane, tenant_id, payload FROM outbox
		WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`)
	if err != nil {
		return 0, err
	}
	type row struct {
		id                int64
		msg, lane, tenant string
		payload           []byte
	}
	var batch []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.id, &r.msg, &r.lane, &r.tenant, &r.payload); err != nil {
			rows.Close()
			return 0, err
		}
		batch = append(batch, r)
	}
	rows.Close()
	for _, r := range batch {
		if err := s.queue.Enqueue(ctx, Job{Stage: "plan", Lane: r.lane, TenantID: r.tenant, MessageID: r.msg, Payload: r.payload}); err != nil {
			return 0, err
		}
		if _, err := tx.Exec(ctx, `UPDATE outbox SET published_at=now(), payload='\x'::bytea WHERE id=$1`, r.id); err != nil {
			return 0, err
		}
	}
	return len(batch), tx.Commit(ctx)
}
