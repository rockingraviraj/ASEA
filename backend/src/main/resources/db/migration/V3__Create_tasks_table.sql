CREATE TABLE IF NOT EXISTS tasks (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL UNIQUE,
    repository_id   BIGINT,
    title           TEXT NOT NULL,
    description     TEXT,
    issue_url       VARCHAR(500),
    trigger         VARCHAR(50),
    task_type       VARCHAR(50) NOT NULL,
    priority        INTEGER DEFAULT 0,
    status          VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    current_state   VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    assigned_agent  VARCHAR(50),
    budget_tokens   BIGINT,
    budget_cost     NUMERIC(12,4),
    max_attempts    INTEGER DEFAULT 3,
    max_execution_ms BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP,
    metadata        JSONB DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_tasks_tenant ON tasks(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_repo ON tasks(repository_id);

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_repo
    FOREIGN KEY (repository_id) REFERENCES repositories(id);
