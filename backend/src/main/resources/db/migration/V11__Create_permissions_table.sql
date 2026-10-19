CREATE TABLE IF NOT EXISTS permissions (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    role_name       VARCHAR(50) NOT NULL,
    agent_type      VARCHAR(50),
    operation       VARCHAR(100) NOT NULL,
    allowed         BOOLEAN NOT NULL DEFAULT true,
    description     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_permissions_tenant ON permissions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_permissions_role ON permissions(role_name);