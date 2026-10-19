package com.asea.task;

import com.asea.state.TaskState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Task} entities.
 *
 * <p><b>Tenant isolation rule:</b> all production access paths must use tenant-scoped
 * methods (those accepting {@code tenantId}) rather than global ID-only lookups.
 * The global {@link #findByTaskId(String)} method exists only for internal infrastructure
 * use (e.g., event consumers that load the task using its own stored tenantId from the DB).
 * Service-layer operations must use {@link #findByTaskIdAndTenantId(String, String)}.</p>
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * Global task lookup by public task ID.
     *
     * <p><b>Security note:</b> do NOT use this method in tenant-facing service operations.
     * Use {@link #findByTaskIdAndTenantId(String, String)} for all tenant-scoped access.
     * This method is reserved for internal infrastructure (e.g., async workers that load
     * the persisted task and then use its own stored tenantId).</p>
     */
    Optional<Task> findByTaskId(String taskId);

    /**
     * Tenant-scoped task lookup by public task ID.
     * Returns empty if the task does not exist OR belongs to a different tenant.
     * Use this for all service-layer operations to enforce tenant isolation.
     */
    Optional<Task> findByTaskIdAndTenantId(String taskId, String tenantId);

    Page<Task> findByTenantId(String tenantId, Pageable pageable);

    Page<Task> findByTenantIdAndStatus(String tenantId, TaskState status, Pageable pageable);

    List<Task> findByTenantIdAndCurrentState(String tenantId, TaskState currentState);

    long countByTenantIdAndStatus(String tenantId, TaskState status);

    @Query("SELECT COUNT(t) FROM Task t WHERE t.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") String tenantId);

    @Query("SELECT t FROM Task t WHERE t.tenantId = :tenantId " +
           "AND (t.title LIKE %:q% OR t.taskId LIKE %:q% OR t.description LIKE %:q%)")
    List<Task> search(@Param("tenantId") String tenantId, @Param("q") String q);
}