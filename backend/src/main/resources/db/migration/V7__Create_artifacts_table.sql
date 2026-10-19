CREATE TABLE IF NOT EXISTS artifacts (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    artifact_type   VARCHAR(50) NOT NULL,
    artifact_name   VARCHAR(255) NOT NULL,
    content         TEXT,
    content_hash    VARCHAR(64),
    storage_ref    VARCHAR(500),
    created_by      VARCHAR(50),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artifacts_task ON artifacts(task_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_tenant ON artifacts(tenant_id);