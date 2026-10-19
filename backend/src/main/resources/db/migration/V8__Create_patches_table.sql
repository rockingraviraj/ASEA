CREATE TABLE IF NOT EXISTS patches (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(50) NOT NULL,
    task_id         VARCHAR(100) NOT NULL,
    patch_id        VARCHAR(100) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PROPOSED',
    files_changed   INTEGER DEFAULT 0,
    lines_added     INTEGER DEFAULT 0,
    lines_removed   INTEGER DEFAULT 0,
    sensitive_files INTEGER DEFAULT 0,
    diff            TEXT,
    policy_check    VARCHAR(50),
    permission_check VARCHAR(50),
    applied_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_patches_task ON patches(task_id);
CREATE INDEX IF NOT EXISTS idx_patches_tenant ON patches(tenant_id);