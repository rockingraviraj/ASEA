CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    actor           VARCHAR(100) NOT NULL,
    task_id         VARCHAR(100),
    previous_state  VARCHAR(50),
    new_state       VARCHAR(50),
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_task ON audit_logs(task_id);
CREATE INDEX IF NOT EXISTS idx_audit_tenant ON audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_event ON audit_logs(event_type);