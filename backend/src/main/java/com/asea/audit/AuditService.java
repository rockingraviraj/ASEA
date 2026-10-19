package com.asea.audit;

import com.asea.security.TenantResolver;
import com.asea.state.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Central audit recording service.
 *
 * <p><b>Transactional Consistency:</b>
 * <ul>
 *   <li><b>Business events</b> (e.g. {@code TASK_CREATED}, {@code STATE_CHANGED}, {@code TASK_COMPLETED})
 *       run in {@link Propagation#REQUIRED} so they participate in the business transaction.
 *       If the transaction rolls back, the success audit record rolls back with it, preventing false
 *       audit records for uncommitted operations.</li>
 *   <li><b>Security and failure events</b> (e.g. {@code POLICY_DENIED}, {@code PERMISSION_DENIED},
 *       {@code BUDGET_EXCEEDED}, {@code INVALID_TRANSITION_ATTEMPTED}) run in {@link Propagation#REQUIRES_NEW}
 *       so security incidents and denied attempts are committed independently and preserved.</li>
 * </ul>
 * </p>
 *
 * <p><b>Tenant Isolation:</b> Audit reads are strictly tenant-scoped. Public/service-facing read
 * methods derive tenant identity server-side from {@link TenantResolver}.</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final Set<AuditEventType> SECURITY_AND_FAILURE_EVENTS = Set.of(
            AuditEventType.POLICY_DENIED,
            AuditEventType.PERMISSION_DENIED,
            AuditEventType.BUDGET_EXCEEDED,
            AuditEventType.INVALID_TRANSITION_ATTEMPTED,
            AuditEventType.HUMAN_APPROVAL_REJECTED,
            AuditEventType.HUMAN_APPROVAL_EXPIRED,
            AuditEventType.SECURITY_VIOLATION_DETECTED,
            AuditEventType.TASK_FAILED,
            AuditEventType.AGENT_FAILED,
            AuditEventType.TOOL_FAILED,
            AuditEventType.VALIDATION_FAILED,
            AuditEventType.RETRY_EXHAUSTED
    );

    private final AuditLogRepository auditLogRepository;
    private final TenantResolver tenantResolver;

    public AuditService(AuditLogRepository auditLogRepository, TenantResolver tenantResolver) {
        this.auditLogRepository = auditLogRepository;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Records an audit event using the appropriate transactional propagation based on the event type:
     * <ul>
     *   <li>Business events run in {@link Propagation#REQUIRED} (rolled back if business transaction fails).</li>
     *   <li>Security denial/failure events run in {@link Propagation#REQUIRES_NEW} (persisted independently).</li>
     * </ul>
     */
    public AuditLog record(String tenantId,
                           AuditEventType eventType,
                           String actor,
                           String taskId,
                           TaskState previousState,
                           TaskState newState,
                           String metadata) {
        if (isSecurityOrFailureEvent(eventType)) {
            return recordIndependent(tenantId, eventType, actor, taskId, previousState, newState, metadata);
        } else {
            return recordBusinessEvent(tenantId, eventType, actor, taskId, previousState, newState, metadata);
        }
    }

    /**
     * Convenience overload for events without state or task.
     */
    public AuditLog record(String tenantId, AuditEventType eventType, String actor, String metadata) {
        return record(tenantId, eventType, actor, null, null, null, metadata);
    }

    /**
     * Records a business event in the current transaction ({@link Propagation#REQUIRED}).
     * If the enclosing business transaction rolls back, this audit log rolls back with it.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog recordBusinessEvent(String tenantId,
                                        AuditEventType eventType,
                                        String actor,
                                        String taskId,
                                        TaskState previousState,
                                        TaskState newState,
                                        String metadata) {
        return persistLog(tenantId, eventType, actor, taskId, previousState, newState, metadata);
    }

    /**
     * Records a security denial or failure event in a new independent transaction ({@link Propagation#REQUIRES_NEW}).
     * Guaranteed to persist even if the outer business transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog recordIndependent(String tenantId,
                                      AuditEventType eventType,
                                      String actor,
                                      String taskId,
                                      TaskState previousState,
                                      TaskState newState,
                                      String metadata) {
        return persistLog(tenantId, eventType, actor, taskId, previousState, newState, metadata);
    }

    private AuditLog persistLog(String tenantId,
                                AuditEventType eventType,
                                String actor,
                                String taskId,
                                TaskState previousState,
                                TaskState newState,
                                String metadata) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(tenantId);
        auditLog.setEventType(eventType);
        auditLog.setActor(actor != null ? actor : "SYSTEM");
        auditLog.setTaskId(taskId);
        auditLog.setPreviousState(previousState);
        auditLog.setNewState(newState);
        auditLog.setMetadata(metadata);
        auditLog.setCreatedAt(LocalDateTime.now());

        AuditLog saved = auditLogRepository.save(auditLog);
        log.debug("Audit recorded eventType={} taskId={} actor={} tenantId={}",
                eventType, taskId, actor, tenantId);
        return saved;
    }

    public boolean isSecurityOrFailureEvent(AuditEventType eventType) {
        return eventType != null && SECURITY_AND_FAILURE_EVENTS.contains(eventType);
    }

    /**
     * Retrieves audit logs for a task scoped strictly to the authenticated tenant.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditLogsForTask(String taskId) {
        String tenantId = tenantResolver.resolveRequiredTenantId();
        return auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(tenantId, taskId);
    }

    /**
     * Retrieves audit logs for a task validating an optional tenant assertion.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditLogsForTask(String taskId, String assertedTenantId) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        return auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(tenantId, taskId);
    }

    /**
     * Internal infrastructure use only — caller must supply a verified tenant ID derived from
     * trusted persisted task data.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditLogsForTaskInternal(String tenantId, String taskId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId must not be blank for internal audit lookup");
        }
        return auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(tenantId, taskId);
    }
}
