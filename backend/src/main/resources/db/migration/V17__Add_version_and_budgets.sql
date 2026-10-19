-- Add optimistic locking version column to tasks table
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Add budget tracking tables
CREATE TABLE IF NOT EXISTS budgets (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL UNIQUE,
    max_tokens      BIGINT,
    max_cost        NUMERIC(12,4),
    max_attempts    INTEGER DEFAULT 3,
    max_execution_ms BIGINT,
    used_tokens     BIGINT DEFAULT 0,
    used_cost       NUMERIC(12,4) DEFAULT 0.0000,
    current_attempts INTEGER DEFAULT 0,
    started_at      TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_budgets_task ON budgets(task_id);
CREATE INDEX IF NOT EXISTS idx_budgets_tenant ON budgets(tenant_id);
