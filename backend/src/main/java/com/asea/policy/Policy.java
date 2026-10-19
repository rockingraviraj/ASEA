package com.asea.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A policy rule that governs whether a given action is permissible.
 *
 * <p>The Policy Engine evaluates actions against policy rules.
 * Policies are separate from Permissions — policies answer "Is this action allowed in principle?"
 * while Permissions answer "Is this specific actor allowed to do it?"</p>
 */
@Entity
@Table(name = "policies", indexes = {
        @Index(name = "idx_policies_tenant", columnList = "tenant_id")
})
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Column(name = "allowed", nullable = false)
    private boolean allowed = true;

    @Column(name = "requires_approval")
    private boolean requiresApproval = false;

    @Column(name = "max_modifications")
    private Integer maxModifications;

    @Column(name = "allowed_paths", columnDefinition = "TEXT")
    private String allowedPaths;

    @Column(name = "sensitive_paths", columnDefinition = "TEXT")
    private String sensitivePaths;

    @Column(name = "execution_restrictions", columnDefinition = "TEXT")
    private String executionRestrictions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Policy() {
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
    public Integer getMaxModifications() { return maxModifications; }
    public void setMaxModifications(Integer maxModifications) { this.maxModifications = maxModifications; }
    public String getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(String allowedPaths) { this.allowedPaths = allowedPaths; }
    public String getSensitivePaths() { return sensitivePaths; }
    public void setSensitivePaths(String sensitivePaths) { this.sensitivePaths = sensitivePaths; }
    public String getExecutionRestrictions() { return executionRestrictions; }
    public void setExecutionRestrictions(String executionRestrictions) { this.executionRestrictions = executionRestrictions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
