package com.asea.audit;

import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.security.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService Tenant Isolation Tests")
class AuditServiceTest {

    private static final String AUTH_TENANT = "tenant-alpha";
    private static final String OTHER_TENANT = "tenant-beta";

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TenantResolver tenantResolver;

    @InjectMocks
    private AuditService auditService;

    @Test
    @DisplayName("getAuditLogsForTask queries logs strictly scoped to authenticated tenant")
    void getAuditLogsForTask_ScopedToAuthenticatedTenant() {
        when(tenantResolver.resolveRequiredTenantId()).thenReturn(AUTH_TENANT);
        AuditLog log1 = new AuditLog();
        log1.setTenantId(AUTH_TENANT);
        log1.setTaskId("ASEA-1234");
        log1.setEventType(AuditEventType.TASK_CREATED);
        log1.setCreatedAt(LocalDateTime.now());

        when(auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-1234"))
                .thenReturn(List.of(log1));

        List<AuditLog> results = auditService.getAuditLogsForTask("ASEA-1234");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTenantId()).isEqualTo(AUTH_TENANT);
        verify(auditLogRepository).findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-1234");
    }

    @Test
    @DisplayName("getAuditLogsForTask with matching assertion queries authenticated tenant logs")
    void getAuditLogsForTask_WithMatchingAssertion_Succeeds() {
        when(tenantResolver.validateAndResolveTenant(AUTH_TENANT)).thenReturn(AUTH_TENANT);
        when(auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-1234"))
                .thenReturn(List.of());

        List<AuditLog> results = auditService.getAuditLogsForTask("ASEA-1234", AUTH_TENANT);

        assertThat(results).isEmpty();
        verify(auditLogRepository).findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-1234");
    }

    @Test
    @DisplayName("getAuditLogsForTask with mismatching assertion throws PermissionDeniedException")
    void getAuditLogsForTask_WithMismatchingAssertion_ThrowsForbidden() {
        when(tenantResolver.validateAndResolveTenant(OTHER_TENANT))
                .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

        assertThatThrownBy(() -> auditService.getAuditLogsForTask("ASEA-1234", OTHER_TENANT))
                .isInstanceOf(PermissionDeniedException.class);

        verify(auditLogRepository, never())
                .findByTenantIdAndTaskIdOrderByCreatedAtAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("getAuditLogsForTask cannot return records across tenant boundaries")
    void getAuditLogsForTask_CannotAccessOtherTenantLogs() {
        when(tenantResolver.resolveRequiredTenantId()).thenReturn(AUTH_TENANT);
        // Repository returns empty because the task logs belong to OTHER_TENANT
        when(auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-OTHER"))
                .thenReturn(List.of());

        List<AuditLog> results = auditService.getAuditLogsForTask("ASEA-OTHER");

        assertThat(results).isEmpty();
        // Verifies the query was strictly scoped to AUTH_TENANT
        verify(auditLogRepository).findByTenantIdAndTaskIdOrderByCreatedAtAsc(AUTH_TENANT, "ASEA-OTHER");
    }

    @Test
    @DisplayName("record() delegates to repository for business events like TASK_CREATED")
    void record_BusinessEvent_SavesLog() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLog saved = auditService.record(
                AUTH_TENANT, AuditEventType.TASK_CREATED, "ACTOR", "ASEA-123", null, null, "{\"msg\":\"ok\"}");

        assertThat(saved.getTenantId()).isEqualTo(AUTH_TENANT);
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.TASK_CREATED);
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("record() delegates to repository for security denial events like POLICY_DENIED")
    void record_DenialEvent_SavesLog() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLog saved = auditService.record(
                AUTH_TENANT, AuditEventType.POLICY_DENIED, "ACTOR", "ASEA-123", null, null, "{\"reason\":\"forbidden\"}");

        assertThat(saved.getTenantId()).isEqualTo(AUTH_TENANT);
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.POLICY_DENIED);
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("record() masks sensitive keys in details JSON")
    void record_MasksSensitiveKeys() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        String inputJson = "{\"token\":\"sk-12345\",\"password\":\"secretPass\",\"apiKey\":\"key-abc\"}";
        AuditLog saved = auditService.record(
                AUTH_TENANT, AuditEventType.SECURITY_ALERT, "ACTOR", "ASEA-123", null, null, inputJson);

        assertThat(saved.getDetails()).doesNotContain("sk-12345");
        assertThat(saved.getDetails()).doesNotContain("secretPass");
        assertThat(saved.getDetails()).doesNotContain("key-abc");
        assertThat(saved.getDetails()).contains("\"token\":\"***REDACTED***\"");
    }
}
