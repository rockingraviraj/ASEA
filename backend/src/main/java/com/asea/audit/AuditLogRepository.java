package com.asea.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link AuditLog} entries.
 *
 * <p><b>Tenant isolation rule:</b> tenant-facing API operations must use tenant-scoped
 * variants. Task-ID-only methods are for internal infrastructure where tenant has been
 * separately verified from the persisted task.</p>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Tenant-scoped task audit history ordered oldest-first.
     * Use this for tenant-facing audit queries.
     */
    List<AuditLog> findByTenantIdAndTaskIdOrderByCreatedAtAsc(String tenantId, String taskId);

    Page<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE a.tenantId = :tenantId AND a.eventType = :eventType " +
           "ORDER BY a.createdAt DESC")
    List<AuditLog> findByTenantIdAndEventType(@Param("tenantId") String tenantId,
                                              @Param("eventType") AuditEventType eventType);

    /**
     * Internal use only — caller must have separately verified tenant from persisted task.
     */
    List<AuditLog> findByTaskIdOrderByCreatedAtAsc(String taskId);

    /**
     * Internal use only — for infrastructure-level record counts.
     */
    long countByTaskId(String taskId);

    /**
     * Tenant-scoped count for a task — safe for tenant-facing operations.
     */
    long countByTenantIdAndTaskId(String tenantId, String taskId);
}
