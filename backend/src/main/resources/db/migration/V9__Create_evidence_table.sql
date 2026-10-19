CREATE TABLE IF NOT EXISTS evidence (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    evidence_type   VARCHAR(50) NOT NULL,
    title           VARCHAR(255),
    description     TEXT,
    artifact_ref    VARCHAR(500),
    metadata        JSONB DEFAULT '{}',
    created_by      VARCHAR(50),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evidence_task ON evidence(task_id);
CREATE INDEX IF NOT EXISTS idx_evidence_tenant ON evidence(tenant_id);