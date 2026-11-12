package commsplane

// Prometheus text metrics (comms-design.md §10, the B0 subset): message transitions per
// event x channel x status, queue depth and oldest-job age per lane and stage, breaker state.

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
)

type metrics struct {
	mu       sync.Mutex
	statuses map[string]int64 // event|channel|status
}

func newMetrics() *metrics { return &metrics{statuses: map[string]int64{}} }

func (m *metrics) status(event, channel, status string) {
	m.mu.Lock()
	m.statuses[event+"|"+channel+"|"+status]++
	m.mu.Unlock()
}

func esc(s string) string { return strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`).Replace(s) }

func (s *Server) getMetrics(w http.ResponseWriter, r *http.Request) {
	var b strings.Builder
	b.WriteString("# HELP comms_message_transitions_total Message state transitions since start.\n# TYPE comms_message_transitions_total counter\n")
	s.metrics.mu.Lock()
	keys := make([]string, 0, len(s.metrics.statuses))
	for k := range s.metrics.statuses {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		p := strings.SplitN(k, "|", 3)
		fmt.Fprintf(&b, "comms_message_transitions_total{event=\"%s\",channel=\"%s\",status=\"%s\"} %d\n", esc(p[0]), esc(p[1]), esc(p[2]), s.metrics.statuses[k])
	}
	s.metrics.mu.Unlock()
	b.WriteString("# HELP comms_queue_depth Jobs waiting (not dead).\n# TYPE comms_queue_depth gauge\n")
	b.WriteString("# HELP comms_queue_oldest_age_seconds Age of the oldest waiting job.\n# TYPE comms_queue_oldest_age_seconds gauge\n")
	rows, err := s.pool.Query(r.Context(), `SELECT stage, lane, count(*), COALESCE(EXTRACT(EPOCH FROM now()-min(created_at)),0)
		FROM jobs WHERE NOT dead GROUP BY stage, lane ORDER BY stage, lane`)
	if err == nil {
		for rows.Next() {
			var stage, lane string
			var n int64
			var age float64
			if rows.Scan(&stage, &lane, &n, &age) == nil {
				fmt.Fprintf(&b, "comms_queue_depth{stage=\"%s\",lane=\"%s\"} %d\n", stage, lane, n)
				fmt.Fprintf(&b, "comms_queue_oldest_age_seconds{stage=\"%s\",lane=\"%s\"} %.3f\n", stage, lane, age)
			}
		}
		rows.Close()
	}
	var dlq int64
	_ = s.pool.QueryRow(r.Context(), `SELECT count(*) FROM jobs WHERE dead`).Scan(&dlq)
	fmt.Fprintf(&b, "# HELP comms_dlq_size Dead-lettered jobs.\n# TYPE comms_dlq_size gauge\ncomms_dlq_size %d\n", dlq)
	b.WriteString("# HELP comms_provider_up 1 when the channel's provider breaker is closed.\n# TYPE comms_provider_up gauge\n")
	chs := make([]string, 0, len(s.providers))
	for ch := range s.providers {
		chs = append(chs, ch)
	}
	sort.Strings(chs)
	for _, ch := range chs {
		up := 0
		if s.breakers[ch].Allow() {
			up = 1
		}
		fmt.Fprintf(&b, "comms_provider_up{channel=\"%s\",provider=\"%s\"} %d\n", ch, s.providers[ch].Kind(), up)
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	w.Write([]byte(b.String()))
}
