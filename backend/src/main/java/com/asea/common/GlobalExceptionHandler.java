package com.asea.common;

import com.asea.common.exceptions.AseaException;
import com.asea.common.exceptions.BudgetExceededException;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.common.exceptions.PolicyViolationException;
import com.asea.common.exceptions.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Centralized exception handling for all ASEA REST endpoints.
 *
 * <p>Ensures consistent error responses across the API. Sensitive details
 * (stack traces, internal entity IDs, DB error messages) are NEVER returned
 * in the response body. Only a structured {@link ApiError} record is returned.</p>
 *
 * <p>All 5xx errors are logged at ERROR level; 4xx errors at WARN or DEBUG.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // Domain exceptions — 4xx
    // -------------------------------------------------------------------------

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EntityNotFoundException ex,
                                                    HttpServletRequest request) {
        log.debug("Entity not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(InvalidTransitionException ex,
                                                             HttpServletRequest request) {
        log.warn("Invalid state transition: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "INVALID_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(PolicyViolationException.class)
    public ResponseEntity<ApiError> handlePolicyViolation(PolicyViolationException ex,
                                                           HttpServletRequest request) {
        log.warn("Policy violation: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, "POLICY_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler(com.asea.common.exceptions.ApprovalRequiredException.class)
    public ResponseEntity<ApiError> handleApprovalRequired(com.asea.common.exceptions.ApprovalRequiredException ex,
                                                           HttpServletRequest request) {
        log.warn("Human approval required: {}", ex.getMessage());
        return error(HttpStatus.PRECONDITION_REQUIRED, "HUMAN_APPROVAL_REQUIRED", ex.getMessage(), request);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiError> handlePermissionDenied(PermissionDeniedException ex,
                                                            HttpServletRequest request) {
        log.warn("Permission denied: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<ApiError> handleBudgetExceeded(BudgetExceededException ex,
                                                          HttpServletRequest request) {
        log.warn("Budget exceeded: {}", ex.getMessage());
        return error(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED", ex.getMessage(), request);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(ValidationException ex,
                                                      HttpServletRequest request) {
        log.debug("Validation error: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    // -------------------------------------------------------------------------
    // Spring/Jakarta validation — 400
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.debug("Bean validation failed: {}", message);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        log.debug("Constraint violation: {}", message);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Malformed request body: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is malformed or missing", request);
    }

    // -------------------------------------------------------------------------
    // Spring Security — 403
    // -------------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                        HttpServletRequest request) {
        log.warn("Access denied: {} {}", request.getMethod(), request.getRequestURI());
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", request);
    }

    // -------------------------------------------------------------------------
    // ASEA base exception and fallback — 500
    // -------------------------------------------------------------------------

    @ExceptionHandler(AseaException.class)
    public ResponseEntity<ApiError> handleAseaException(AseaException ex,
                                                         HttpServletRequest request) {
        log.error("ASEA application error code={}: {}", ex.getCode(), ex.getMessage(), ex);
        // Return only the code, not internal details
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ex.getCode(),
                "An application error occurred", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex,
                                                      HttpServletRequest request) {
        // Log with full stack trace for investigation, but never return stack to client
        log.error("Unhandled exception on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request);
    }

    // -------------------------------------------------------------------------

    private ResponseEntity<ApiError> error(HttpStatus status, String code,
                                            String message, HttpServletRequest request) {
        ApiError body = new ApiError(
                status.value(), code, message,
                LocalDateTime.now(),
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
