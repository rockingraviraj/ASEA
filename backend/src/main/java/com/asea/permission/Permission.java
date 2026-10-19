package com.asea.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A permission grant defining what operations an actor (agent type) may perform.
 *
 * <p>Permissions are separate from Policies:
 * Policy = "Is this action allowed in principle?" (rule-based)
 * Permission = "Is this specific actor allowed to perform this operation?" (actor-based)</p>
 */
@Entity
@Table(name = "permissions", indexes = {
        @Index(name = "idx_permissions_tenant", columnList = "tenant_id")
})
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "actor_type", nullable = false, length = 100)
    private String actorType;

    @Column(name = "operation", nullable = false, length = 100)
    private String operation;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "read_only")
    private boolean readOnly;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Permission() {
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
