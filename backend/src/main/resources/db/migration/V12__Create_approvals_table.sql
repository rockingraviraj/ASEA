CREATE TABLE IF NOT EXISTS approvals (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    approval_id     VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by    VARCHAR(50),
    approved_by     VARCHAR(100),
    reason          TEXT,
    feedback        TEXT,
    escalation_type VARCHAR(50),
    risk_level      VARCHAR(20),
    requested_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_approvals_task ON approvals(task_id);
CREATE INDEX IF NOT EXISTS idx_approvals_tenant ON approvals(tenant_id);