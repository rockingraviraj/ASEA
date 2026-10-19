CREATE TABLE IF NOT EXISTS model_runs (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    model           VARCHAR(100) NOT NULL,
    prompt          TEXT,
    response        TEXT,
    tokens_used     BIGINT,
    cost            NUMERIC(12,4),
    latency_ms      BIGINT,
    success         BOOLEAN,
    error           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_model_runs_task ON model_runs(task_id);
CREATE INDEX IF NOT EXISTS idx_model_runs_tenant ON model_runs(tenant_id);