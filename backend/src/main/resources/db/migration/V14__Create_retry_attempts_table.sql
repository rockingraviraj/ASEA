CREATE TABLE IF NOT EXISTS retry_attempts (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    attempt_number  INTEGER NOT NULL,
    hypothesis      TEXT,
    plan            TEXT,
    patch_ref       VARCHAR(255),
    failure         TEXT,
    failure_type    VARCHAR(50),
    evidence        TEXT,
    retry_reason    TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP,
    UNIQUE(task_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_retry_task ON retry_attempts(task_id);
CREATE INDEX IF NOT EXISTS idx_retry_tenant ON retry_attempts(tenant_id);