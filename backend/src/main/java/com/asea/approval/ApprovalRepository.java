package com.asea.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Approval} entities.
 *
 * <p><b>Tenant isolation rule:</b> tenant-facing lookups must use tenant-scoped methods.
 * Global lookups by approval ID alone are internal infrastructure only.</p>
 */
@Repository
public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    /** Tenant-scoped approval lookup by approvalId. */
    Optional<Approval> findByTenantIdAndApprovalId(String tenantId, String approvalId);

    Optional<Approval> findByTenantIdAndTaskIdAndApprovalId(String tenantId, String taskId, String approvalId);

    List<Approval> findByTenantIdAndTaskId(String tenantId, String taskId);

    Optional<Approval> findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
            String tenantId, String taskId, String status);

    Optional<Approval> findTopByTenantIdAndTaskIdAndEscalationTypeAndStatusOrderByRequestedAtDesc(
            String tenantId, String taskId, String escalationType, String status);

    /** Internal use only — caller must separately verify tenant from persisted task. */
    Optional<Approval> findByApprovalId(String approvalId);
}
