# ASEA Division A — Stage 1 Progress Report: Tenant Isolation & Baseline Hardening

**Date:** 2026-10-19  
**Stage:** Stage 1 — P0 Tenant Isolation, Regression Tests, and Baseline Verification  
**Status:** COMPLETE (Build and Tests Passing)  

---

## 1. Executive Summary

Stage 1 P0 tenant isolation for ASEA Division A backend has been fully implemented, verified, and regression tested. All critical security requirements have been addressed:
- The authoritative tenant identity is strictly derived server-side from the authenticated `AseaPrincipal` via `TenantResolver`.
- Request-body and query-parameter tenant assertions are validated against the authenticated tenant; matching assertions are accepted without altering ownership, while mismatching assertions return HTTP 403 Forbidden.
- Cross-tenant data lookups return non-disclosing HTTP 404 Not Found.
- Audit log reads are strictly tenant-scoped through `AuditService` and `AuditLogRepository`.
- All service-level repository operations across tasks, budgets, state transitions, retry attempts, and approvals enforce tenant isolation.
- The existing failure in `PolicyEngineTest` (`largeModification_HighRiskApprovalRequired`) has been resolved at root cause.
- A comprehensive test suite containing 138 tests (132 passed, 0 failures, 0 errors, 6 skipped Testcontainers tests due to environment Docker unavailability) has been verified via Maven.

---

## 2. Actual Files Changed & Added

### Production Source (`src/main/java`)
1. **`com.asea.task.dto.TaskCreateRequest`**
   - Added `@JsonProperty("tenantId") String tenantAssertion` to accept legacy request-body tenant assertions without re-introducing `tenantId` as a trusted ownership field.
   - Provided an overloaded constructor preserving full backward compatibility.
   - Added OpenAPI `@Schema` documentation clarifying that the field is an optional assertion only.

2. **`com.asea.task.TaskService`**
   - Updated `createTask()` to validate `request.tenantAssertion()` via `tenantResolver.validateAndResolveTenant(request.tenantAssertion())`.
   - Rejects mismatching assertions immediately with `PermissionDeniedException` (HTTP 403) before any database write or budget initialization occurs.
   - Derives persisted task ownership exclusively from the authenticated server-side principal.

3. **`com.asea.task.TaskController`**
   - Updated `@Operation` description for `createTask` to document that request-body `tenantId` assertions are validated for tenant consistency (returning 403 on mismatch) and never override ownership.

4. **`com.asea.audit.AuditService`**
   - Constructor-injected `TenantResolver`.
   - Updated public `getAuditLogsForTask(String taskId)` to resolve the authenticated tenant server-side and invoke `auditLogRepository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(tenantId, taskId)`.
   - Added `getAuditLogsForTask(String taskId, String assertedTenantId)` to validate optional caller assertions.
   - Added `getAuditLogsForTaskInternal(String tenantId, String taskId)` reserved for trusted internal infrastructure (e.g. background reconcilers).

5. **`com.asea.approval.ApprovalRepository`**
   - Added tenant-scoped lookup `findByTenantIdAndApprovalId(String tenantId, String approvalId)`.
   - Documented `findByApprovalId(String approvalId)` as internal-only.

6. **`com.asea.policy.PolicyEngine`**
   - Fixed bug where `requiresApproval` remained `false` when modifications exceeded 500 lines on files already classified as HIGH risk (`src/main/`).
   - Moved `requiresApproval = true` outside the ordinal comparison check so that any change exceeding 500 lines unconditionally escalates to require human approval.

### Test Source (`src/test/java`)
1. **`com.asea.security.TenantResolverTest`** (15 tests)
   - Unit tests covering all fail-closed security conditions: missing security context, unauthenticated tokens, anonymous tokens, unrecognized principal types, null/blank tenant IDs, matching assertions, and mismatching assertions.
2. **`com.asea.task.TaskServiceTest`** (16 tests)
   - JUnit 5 / Mockito unit tests verifying:
     - `createTask` derives tenant ownership from `TenantResolver` and initializes budget with matching tenant/taskId.
     - Body tenant assertions matching authenticated tenant succeed.
     - Body tenant assertions mismatching authenticated tenant fail fast with 403 before repository access.
     - `getTask` uses `findByTaskIdAndTenantId` and returns 404 for other-tenant task IDs.
     - `listTasks` scopes queries by authenticated tenant for both filtered and unfiltered requests.
     - `getTaskHistory` verifies tenant ownership before querying history.
     - `transitionTask` validates state transitions, enforces tenant scope, and logs tenant-consistent audit entries.
3. **`com.asea.audit.AuditServiceTest`** (4 tests)
   - Unit tests proving audit reads are scoped strictly to the authenticated tenant, validate assertions (403 on mismatch), and reject cross-tenant data access.
4. **`com.asea.task.TaskControllerSecurityTest`** (16 tests)
   - WebMvcTest exercising the real Spring Security filter chain (`ApiKeyAuthenticationFilter`), Spring MVC endpoints, and `GlobalExceptionHandler`:
     - Missing API key -> 401 Unauthorized.
     - Invalid API key -> 401 Unauthorized.
     - Valid API key & normal task creation -> 201 Created.
     - Task creation without body `tenantId` -> 201 Created.
     - Task creation with matching legacy assertion -> 201 Created.
     - Task creation with mismatching legacy assertion -> 403 Forbidden.
     - GET task with no query assertion -> 200 OK scoped to auth tenant.
     - GET task with matching assertion -> 200 OK.
     - GET task with mismatching assertion -> 403 Forbidden.
     - Cross-tenant task ID -> non-disclosing 404 Not Found.
     - Tenant-scoped task listing does not leak other tenant rows.
     - Illegal state transition -> 409 Conflict without state mutation.
5. **`com.asea.repository.PostgresTenantIsolationIntegrationTest`** (6 tests)
   - Testcontainers PostgreSQL integration test verifying schema migrations and multi-tenant database isolation for Task, StateTransition, AuditLog, Budget, RetryAttempt, and Approval entities.
   - Configured with `@Testcontainers(disabledWithoutDocker = true)` to avoid failing builds when Docker daemon is not active.

---

## 3. Commands Executed & Outcomes

| Command | Working Directory | Exit Code | Result Summary |
|---|---|:---:|---|
| `.\mvnw.cmd test -Dtest=PolicyEngineTest` | `backend` | 0 | 15 tests passed, 0 failures. Root-cause fix verified. |
| `.\mvnw.cmd test -Dtest=TaskControllerSecurityTest` | `backend` | 0 | 16 tests passed, 0 failures. Security chain and endpoints verified. |
| `.\mvnw.cmd test` | `backend` | 0 | 138 tests run: 132 passed, 0 failures, 0 errors, 6 skipped. |
| `.\mvnw.cmd verify` | `backend` | 0 | Full lifecycle execution passed; packaged Spring Boot executable JAR `target/backend-1.0.0-SNAPSHOT.jar`. |

---

## 4. Test Results Matrix

| Test Suite Class | Tests Run | Passed | Failed | Errors | Skipped | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| `com.asea.policy.PolicyEngineTest` | 15 | 15 | 0 | 0 | 0 | **PASSED** |
| `com.asea.security.TenantResolverTest` | 15 | 15 | 0 | 0 | 0 | **PASSED** |
| `com.asea.state.StateGraphTest` | 66 | 66 | 0 | 0 | 0 | **PASSED** |
| `com.asea.audit.AuditServiceTest` | 4 | 4 | 0 | 0 | 0 | **PASSED** |
| `com.asea.task.TaskServiceTest` | 16 | 16 | 0 | 0 | 0 | **PASSED** |
| `com.asea.task.TaskControllerSecurityTest` | 16 | 16 | 0 | 0 | 0 | **PASSED** |
| `com.asea.repository.PostgresTenantIsolationIntegrationTest` | 6 | 0 | 0 | 0 | 6 | **BLOCKED / NOT VERIFIED** (Docker unavailable) |
| **TOTAL** | **138** | **132** | **0** | **0** | **6** | **BUILD SUCCESS** |

> **Note on Testcontainers:** The PostgreSQL integration tests are fully implemented in `PostgresTenantIsolationIntegrationTest.java`. Because Docker is not active in the current host environment (`open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`), these 6 tests were automatically skipped as intended via `@Testcontainers(disabledWithoutDocker = true)`.

---

## 5. Explicit List of Remaining Gaps & Next Stages

1. **Async Worker Tenant Verification:**
   - **Status:** DEFERRED to the Redis Streams integration stage.
   - **Requirement:** When the asynchronous consumer/worker is implemented, it must not trust tenant identifiers passed in event payloads. The worker must load the persisted task by taskId through a trusted, tenant-safe internal path and take the canonical tenant ID exclusively from persisted task data.

2. **Control-Plane Transition Endpoint Protection:**
   - **Status:** RECORDED for Division A control-plane / Orchestrator stage.
   - **Note:** The `/api/v1/tasks/{taskId}/transition` endpoint currently enforces tenant boundary and state graph validity, but direct external caller access must later be restricted so external clients cannot bypass the Orchestrator-owned workflow transitions.

3. **Multi-Tenant Identity Architecture:**
   - **Status:** RECORDED for production security hardening.
   - **Note:** The current system uses API-key authentication with a server-configured tenant mapping. True multi-tenant JWT / OAuth2 OIDC token validation will be integrated in subsequent identity milestones.
