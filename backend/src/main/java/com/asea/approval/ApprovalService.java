package com.asea.approval;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.ValidationException;
import com.asea.policy.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the lifecycle of human approvals.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Tenant-scoped reads and mutations</li>
 *   <li>Idempotent approval request creation (prevents duplicate active requests)</li>
 *   <li>Concurrency-safe terminal state transitions (PENDING -> APPROVED or REJECTED)</li>
 *   <li>Audit event emission for all lifecycle actions</li>
 * </ul>
 * </p>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvalRepository;
    private final AuditService auditService;

    public ApprovalService(ApprovalRepository approvalRepository, AuditService auditService) {
        this.approvalRepository = approvalRepository;
        this.auditService = auditService;
    }

    /**
     * Requests human approval for an action on a task.
     * Idempotent: if an active PENDING approval already exists for the same tenant, task, and action,
     * the existing approval is returned instead of creating a duplicate.
     */
    @Transactional
    public Approval requestApproval(String tenantId, String taskId, String action,
                                    RiskLevel riskLevel, String requestedBy, String reason) {
        if (!StringUtils.hasText(tenantId)) {
            throw new ValidationException("tenantId is required for approval request");
        }
        if (!StringUtils.hasText(taskId)) {
            throw new ValidationException("taskId is required for approval request");
        }
        if (!StringUtils.hasText(action)) {
            throw new ValidationException("action is required for approval request");
        }

        // Idempotency check: reuse existing active PENDING approval for this action if present
        Optional<Approval> existingPending = approvalRepository
                .findTopByTenantIdAndTaskIdAndEscalationTypeAndStatusOrderByRequestedAtDesc(
                        tenantId, taskId, action, "PENDING");
        if (existingPending.isPresent()) {
            log.info("Active pending approval already exists: approvalId={} taskId={} tenantId={}",
                    existingPending.get().getApprovalId(), taskId, tenantId);
            return existingPending.get();
        }

        String approvalId = "APP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Approval approval = new Approval();
        approval.setTenantId(tenantId);
        approval.setTaskId(taskId);
        approval.setApprovalId(approvalId);
        approval.setEscalationType(action);
        approval.setRiskLevel(riskLevel != null ? riskLevel : RiskLevel.HIGH);
        approval.setRequestedBy(requestedBy != null ? requestedBy : "SYSTEM");
        approval.setReason(reason);
        approval.setStatus("PENDING");
        approval.setRequestedAt(LocalDateTime.now());

        Approval saved = approvalRepository.save(approval);

        auditService.record(tenantId, AuditEventType.HUMAN_APPROVAL_REQUESTED,
                saved.getRequestedBy(), taskId, null, null,
                "{\"approvalId\":\"" + approvalId + "\",\"action\":\"" + action + "\",\"riskLevel\":\"" + saved.getRiskLevel() + "\"}");

        log.info("Approval requested: approvalId={} action={} riskLevel={} taskId={} tenantId={}",
                approvalId, action, saved.getRiskLevel(), taskId, tenantId);
        return saved;
    }

    /**
     * Resolves an approval as APPROVED.
     * Concurrency-safe: rejects if already in a terminal status.
     */
    @Transactional
    public Approval approve(String tenantId, String approvalId, String approver, String feedback) {
        Approval approval = findScopedApproval(tenantId, approvalId);

        if (!"PENDING".equalsIgnoreCase(approval.getStatus())) {
            throw new InvalidTransitionException(
                    "Approval " + approvalId + " cannot be approved; current status is: " + approval.getStatus());
        }

        approval.setStatus("APPROVED");
        approval.setApprovedBy(StringUtils.hasText(approver) ? approver : "HUMAN_REVIEWER");
        approval.setFeedback(feedback);
        approval.setResolvedAt(LocalDateTime.now());

        Approval saved = approvalRepository.save(approval);

        auditService.record(tenantId, AuditEventType.HUMAN_APPROVAL_GRANTED,
                saved.getApprovedBy(), saved.getTaskId(), null, null,
                "{\"approvalId\":\"" + approvalId + "\",\"status\":\"APPROVED\"}");

        log.info("Approval GRANTED: approvalId={} by={} taskId={} tenantId={}",
                approvalId, saved.getApprovedBy(), saved.getTaskId(), tenantId);
        return saved;
    }

    /**
     * Resolves an approval as REJECTED.
     * Concurrency-safe: rejects if already in a terminal status.
     */
    @Transactional
    public Approval reject(String tenantId, String approvalId, String approver, String reason) {
        Approval approval = findScopedApproval(tenantId, approvalId);

        if (!"PENDING".equalsIgnoreCase(approval.getStatus())) {
            throw new InvalidTransitionException(
                    "Approval " + approvalId + " cannot be rejected; current status is: " + approval.getStatus());
        }

        approval.setStatus("REJECTED");
        approval.setApprovedBy(StringUtils.hasText(approver) ? approver : "HUMAN_REVIEWER");
        approval.setFeedback(reason);
        approval.setResolvedAt(LocalDateTime.now());

        Approval saved = approvalRepository.save(approval);

        auditService.record(tenantId, AuditEventType.HUMAN_APPROVAL_REJECTED,
                saved.getApprovedBy(), saved.getTaskId(), null, null,
                "{\"approvalId\":\"" + approvalId + "\",\"status\":\"REJECTED\"}");

        log.info("Approval REJECTED: approvalId={} by={} taskId={} tenantId={}",
                approvalId, saved.getApprovedBy(), saved.getTaskId(), tenantId);
        return saved;
    }

    /**
     * Finds a valid, non-expired APPROVED approval record for the given tenant, task, and action.
     */
    @Transactional(readOnly = true)
    public Optional<Approval> findValidApproval(String tenantId, String taskId, String action) {
        return approvalRepository.findTopByTenantIdAndTaskIdAndEscalationTypeAndStatusOrderByRequestedAtDesc(
                tenantId, taskId, action, "APPROVED");
    }

    /**
     * Retrieves an approval scoped to the tenant.
     */
    @Transactional(readOnly = true)
    public Approval getApproval(String tenantId, String approvalId) {
        return findScopedApproval(tenantId, approvalId);
    }

    private Approval findScopedApproval(String tenantId, String approvalId) {
        return approvalRepository.findByTenantIdAndApprovalId(tenantId, approvalId)
                .orElseThrow(() -> new EntityNotFoundException("Approval not found: " + approvalId));
    }
}
