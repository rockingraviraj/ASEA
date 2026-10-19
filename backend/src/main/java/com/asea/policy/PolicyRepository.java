package com.asea.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findByTenantId(String tenantId);

    Optional<Policy> findByTenantIdAndAction(String tenantId, String action);

    @Query("SELECT p FROM Policy p WHERE p.tenantId = :tenantId AND p.riskLevel = :riskLevel")
    List<Policy> findByTenantIdAndRiskLevel(@Param("tenantId") String tenantId,
                                            @Param("riskLevel") RiskLevel riskLevel);

    @Query("SELECT p FROM Policy p WHERE p.tenantId = :tenantId AND p.requiresApproval = true")
    List<Policy> findApprovalRequiredPolicies(@Param("tenantId") String tenantId);
}
