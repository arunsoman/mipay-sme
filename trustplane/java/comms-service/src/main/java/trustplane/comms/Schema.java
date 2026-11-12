package trustplane.comms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Small B0 schema shared with the existing Go comms database. */
@Component
public class Schema {
    public Schema(JdbcTemplate db) {
        db.execute("""
                CREATE TABLE IF NOT EXISTS tenants(id TEXT PRIMARY KEY, name TEXT NOT NULL,
                    default_locale TEXT NOT NULL DEFAULT 'en', sandbox BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now())
                """);
        db.update("INSERT INTO tenants(id,name) VALUES ('platform','MiPay platform') ON CONFLICT DO NOTHING");
        db.execute("""
                CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants(id),
                    client_id TEXT NOT NULL, idempotency_key TEXT, event_code TEXT NOT NULL, lane TEXT NOT NULL,
                    recipients INTEGER NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())
                """);
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS requests_idem_uq ON requests(tenant_id,client_id,idempotency_key) WHERE idempotency_key IS NOT NULL");
        db.execute("""
                CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, request_id TEXT NOT NULL REFERENCES requests(id),
                    tenant_id TEXT NOT NULL, client_id TEXT NOT NULL, event_code TEXT NOT NULL,
                    recipient_ref TEXT NOT NULL, lane TEXT NOT NULL, category TEXT NOT NULL,
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL', channel TEXT, address_masked TEXT,
                    template_version_id BIGINT, locale TEXT NOT NULL DEFAULT 'en', data_names TEXT[] NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL, status_reason TEXT, provider_id TEXT, provider_msg_id TEXT,
                    expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), seq INTEGER NOT NULL DEFAULT 0)
                """);
        db.execute("CREATE INDEX IF NOT EXISTS messages_request_idx ON messages(request_id)");
        db.execute("""
                CREATE TABLE IF NOT EXISTS message_events(id BIGSERIAL PRIMARY KEY,
                    message_id TEXT NOT NULL REFERENCES messages(id), at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    status TEXT NOT NULL, detail JSONB NOT NULL DEFAULT '{}'::jsonb)
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS contacts(tenant_id TEXT NOT NULL DEFAULT 'platform',
                    subject_ref TEXT NOT NULL, channel TEXT NOT NULL, address_enc BYTEA NOT NULL,
                    address_hash TEXT NOT NULL, address_masked TEXT NOT NULL, verified_at TIMESTAMPTZ,
                    is_primary BOOLEAN NOT NULL DEFAULT FALSE, updated_by TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY(tenant_id,subject_ref,channel,address_hash))
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS sim_outbox(id TEXT PRIMARY KEY, message_id TEXT NOT NULL,
                    event_code TEXT NOT NULL, recipient_ref TEXT NOT NULL, channel TEXT NOT NULL,
                    address TEXT NOT NULL, subject TEXT NOT NULL DEFAULT '', body TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now())
                """);
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS sim_outbox_message_uq ON sim_outbox(message_id)");
        db.execute("""
                CREATE TABLE IF NOT EXISTS comms_java_jobs(message_id TEXT PRIMARY KEY REFERENCES messages(id),
                    payload BYTEA NOT NULL, available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    lease_until TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT)
                """);
    }
}
