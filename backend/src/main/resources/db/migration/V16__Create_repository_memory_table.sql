CREATE TABLE IF NOT EXISTS repository_memory (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    repository_id   BIGINT,
    category        VARCHAR(50) NOT NULL,
    title           VARCHAR(255),
    content         TEXT,
    metadata        JSONB DEFAULT '{}',
    source_task_id  VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_repo ON repository_memory(repository_id);
CREATE INDEX IF NOT EXISTS idx_memory_tenant ON repository_memory(tenant_id);

ALTER TABLE repository_memory ADD CONSTRAINT fk_memory_repo
    FOREIGN KEY (repository_id) REFERENCES repositories(id);