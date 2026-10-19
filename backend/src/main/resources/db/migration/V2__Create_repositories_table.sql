CREATE TABLE IF NOT EXISTS repositories (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    owner           VARCHAR(255) NOT NULL,
    url             VARCHAR(500),
    default_branch  VARCHAR(100) DEFAULT 'main',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_repos_tenant ON repositories(tenant_id);
