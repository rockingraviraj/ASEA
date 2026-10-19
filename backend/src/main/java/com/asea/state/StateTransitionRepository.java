package com.asea.state;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link StateTransition} history records.
 *
 * <p><b>Tenant isolation rule:</b> tenant-facing operations must use the tenant-scoped
 * variants (those accepting both {@code taskId} and {@code tenantId}). The task-ID-only
 * methods are reserved for internal infrastructure usage where the tenantId is separately
 * verified from the persisted task record.</p>
 */
@Repository
public interface StateTransitionRepository extends JpaRepository<StateTransition, Long> {

    /**
     * Tenant-scoped history lookup ordered by creation time ascending.
     * Use this in all service-layer tenant-facing operations.
     */
    List<StateTransition> findByTaskIdAndTenantIdOrderByCreatedAtAsc(String taskId, String tenantId);

    /**
     * Tenant-scoped transition count for a specific target state.
     */
    @Query("SELECT COUNT(st) FROM StateTransition st " +
           "WHERE st.taskId = :taskId AND st.tenantId = :tenantId AND st.toState = :state")
    long countByTaskIdAndTenantIdAndToState(@Param("taskId") String taskId,
                                            @Param("tenantId") String tenantId,
                                            @Param("state") TaskState state);

    /**
     * Tenant-scoped recovery attempt count.
     */
    @Query("SELECT COUNT(st) FROM StateTransition st " +
           "WHERE st.taskId = :taskId AND st.tenantId = :tenantId AND st.toState = 'RECOVERING'")
    long countRecoveryAttemptsByTaskIdAndTenantId(@Param("taskId") String taskId,
                                                  @Param("tenantId") String tenantId);

    // -------------------------------------------------------------------------
    // Internal-only methods — for infrastructure use where tenant is already verified
    // -------------------------------------------------------------------------

    /** Internal use only — caller must have already verified tenant from persisted task. */
    List<StateTransition> findByTaskIdOrderByCreatedAtAsc(String taskId);

    /** Internal use only — caller must have already verified tenant from persisted task. */
    List<StateTransition> findByTaskId(String taskId);

    /** Internal use only. */
    @Query("SELECT COUNT(st) FROM StateTransition st WHERE st.taskId = :taskId AND st.toState = :state")
    long countByTaskIdAndToState(@Param("taskId") String taskId, @Param("state") TaskState state);

    /** Internal use only. */
    @Query("SELECT COUNT(st) FROM StateTransition st WHERE st.taskId = :taskId AND st.toState = 'RECOVERING'")
    long countRecoveryAttempts(@Param("taskId") String taskId);
}