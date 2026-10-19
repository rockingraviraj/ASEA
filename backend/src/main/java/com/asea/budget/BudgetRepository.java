package com.asea.budget;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link Budget} entities.
 *
 * <p><b>Tenant isolation rule:</b> all service-layer operations that check or consume
 * budget must use tenant-scoped methods (those accepting both {@code tenantId} and
 * {@code taskId}). The task-ID-only methods are reserved for internal infrastructure
 * usage where the tenant has been separately verified.</p>
 */
@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /**
     * Tenant-scoped budget read. Use for read-only checks in service operations.
     */
    Optional<Budget> findByTenantIdAndTaskId(String tenantId, String taskId);

    /**
     * Tenant-scoped budget read with pessimistic write lock.
     * Use this for all budget consumption and increment operations to prevent concurrent updates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Budget b WHERE b.tenantId = :tenantId AND b.taskId = :taskId")
    Optional<Budget> findByTenantIdAndTaskIdForUpdate(@Param("tenantId") String tenantId,
                                                      @Param("taskId") String taskId);

    long countByTenantIdAndStatus(String tenantId, String status);

    // -------------------------------------------------------------------------
    // Internal-only methods — reserved for infrastructure use
    // -------------------------------------------------------------------------

    /**
     * Global task-only budget read. Internal use only — caller must separately verify tenant.
     * Do NOT use this in tenant-facing service operations.
     */
    Optional<Budget> findByTaskId(String taskId);

    /**
     * Global task-only budget read with pessimistic lock. Internal use only.
     * Do NOT use this in tenant-facing service operations.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Budget b WHERE b.taskId = :taskId")
    Optional<Budget> findByTaskIdForUpdate(@Param("taskId") String taskId);
}
