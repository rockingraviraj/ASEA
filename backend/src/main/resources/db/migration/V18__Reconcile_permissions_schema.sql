-- V18: Reconcile permissions table to match the Permission entity.
--
-- The original V11 migration defined role_name + agent_type columns, but the
-- Permission.java entity uses actor_type + read_only, which better reflects
-- the ASEA domain model (actor-centric permission grants, not role-based).
--
-- This migration adds the missing columns and drops the superseded ones.
-- The idx_permissions_role index on role_name is also dropped.

-- Add actor_type column to match Permission.actorType
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS actor_type VARCHAR(100);

-- Migrate existing data: copy agent_type → actor_type where role_name exists
UPDATE permissions
SET actor_type = COALESCE(agent_type, role_name)
WHERE actor_type IS NULL;

-- Make actor_type NOT NULL now that data is migrated
ALTER TABLE permissions ALTER COLUMN actor_type SET NOT NULL;

-- Add read_only flag to match Permission.readOnly
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT false;

-- Drop the superseded columns and index
DROP INDEX IF EXISTS idx_permissions_role;
ALTER TABLE permissions DROP COLUMN IF EXISTS role_name;
ALTER TABLE permissions DROP COLUMN IF EXISTS agent_type;

-- Add a composite index for the common lookup pattern: tenant + actor + operation
CREATE INDEX IF NOT EXISTS idx_permissions_actor_op ON permissions(tenant_id, actor_type, operation);
