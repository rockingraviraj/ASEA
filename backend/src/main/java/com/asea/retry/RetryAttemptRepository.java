package com.asea.retry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link RetryAttempt} records.
 *
 * <p><b>Tenant isolation rule:</b> all tenant-facing service operations must use
 * tenant-scoped methods. Task-ID-only methods are internal infrastructure.</p>
 */
@Repository
public interface RetryAttemptRepository extends JpaRepository<RetryAttempt, Long> {

    /** Tenant-scoped: all attempts for a task, ordered by attempt number ascending. */
    List<RetryAttempt> findByTenantIdAndTaskIdOrderByAttemptNumberAsc(String tenantId, String taskId);

    /** Tenant-scoped count. */
    long countByTenantIdAndTaskId(String tenantId, String taskId);

    /** Tenant-scoped max attempt number. */
    @Query("SELECT MAX(r.attemptNumber) FROM RetryAttempt r " +
           "WHERE r.tenantId = :tenantId AND r.taskId = :taskId")
    Integer findMaxAttemptNumberByTenantId(@Param("tenantId") String tenantId,
                                           @Param("taskId") String taskId);

    /** Tenant-scoped pending attempts. */
    @Query("SELECT r FROM RetryAttempt r " +
           "WHERE r.tenantId = :tenantId AND r.taskId = :taskId AND r.status = 'PENDING'")
    List<RetryAttempt> findPendingAttemptsByTenantId(@Param("tenantId") String tenantId,
                                                     @Param("taskId") String taskId);

    // Internal-only methods — caller must separately verify tenant
    List<RetryAttempt> findByTaskIdOrderByAttemptNumberAsc(String taskId);
    long countByTaskId(String taskId);

    @Query("SELECT MAX(r.attemptNumber) FROM RetryAttempt r WHERE r.taskId = :taskId")
    Integer findMaxAttemptNumber(@Param("taskId") String taskId);

    @Query("SELECT r FROM RetryAttempt r WHERE r.taskId = :taskId AND r.status = 'PENDING'")
    List<RetryAttempt> findPendingAttempts(@Param("taskId") String taskId);
}
