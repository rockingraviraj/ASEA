CREATE TABLE IF NOT EXISTS task_states (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(100) NOT NULL,
    tenant_id       VARCHAR(50) NOT NULL,
    from_state      VARCHAR(50),
    to_state        VARCHAR(50) NOT NULL,
    trigger         VARCHAR(100),
    reason          TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_states_task ON task_states(task_id);
CREATE INDEX IF NOT EXISTS idx_states_tenant ON task_states(tenant_id);
