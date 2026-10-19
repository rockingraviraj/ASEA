CREATE TABLE IF NOT EXISTS policies (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    action          VARCHAR(100) NOT NULL,
    risk_level      VARCHAR(20) NOT NULL,
    allowed         BOOLEAN NOT NULL DEFAULT true,
    requires_approval BOOLEAN DEFAULT false,
    max_modifications INTEGER,
    allowed_paths   TEXT,
    sensitive_paths TEXT,
    execution_restrictions TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_policies_tenant ON policies(tenant_id);