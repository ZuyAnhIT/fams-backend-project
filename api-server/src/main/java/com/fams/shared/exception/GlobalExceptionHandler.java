package com.fams.shared.exception;

import com.fams.modules.randomcheck.service.CheckExpiredException;
import com.fams.shared.ai.AiServiceException;
import com.fams.shared.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ApiResponse<Map<String, String>> response = new ApiResponse<>(
                false, "Validation failed", fieldErrors,
                "VALIDATION_ERROR", "Dữ liệu không hợp lệ, vui lòng kiểm tra lại các trường bắt buộc.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(
            HandlerMethodValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Invalid request parameter: " + ex.getMessage(),
                        "VALIDATION_ERROR",
                        "Tham số yêu cầu không hợp lệ."));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode(), ex.getUserMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(
                        "Invalid email or password",
                        "INVALID_CREDENTIALS",
                        "Email hoặc mật khẩu không đúng. Vui lòng thử lại."));
    }

    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlanLimitExceeded(PlanLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "PLAN_LIMIT_EXCEEDED",
                        "Bạn đã đạt giới hạn của gói dịch vụ hiện tại. Vui lòng nâng cấp gói để tiếp tục."));
    }

    @ExceptionHandler(PlanDeactivationBlockedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlanDeactivationBlocked(PlanDeactivationBlockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "PLAN_DEACTIVATION_BLOCKED",
                        ex.getMessage()));
    }

    @ExceptionHandler(TenantSuspendedException.class)
    public ResponseEntity<ApiResponse<Void>> handleTenantSuspended(TenantSuspendedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "TENANT_SUSPENDED",
                        "Tài khoản doanh nghiệp đang bị tạm dừng. Vui lòng liên hệ quản trị viên."));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccountLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiResponse.error(
                        "Account locked until " + ex.getLockedUntil(),
                        "ACCOUNT_LOCKED",
                        "Tài khoản bị khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau " + ex.getLockedUntil() + "."));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateResource(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "DUPLICATE_RESOURCE",
                        "Dữ liệu đã tồn tại trong hệ thống. Vui lòng kiểm tra lại."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Missing required parameter: " + ex.getParameterName(),
                        "MISSING_PARAMETER",
                        "Thiếu tham số bắt buộc: " + ex.getParameterName() + ". Vui lòng kiểm tra lại yêu cầu."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Invalid value for parameter '" + ex.getName() + "'",
                        "INVALID_PARAMETER_TYPE",
                        "Giá trị không hợp lệ cho tham số '" + ex.getName() + "'."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "INVALID_ARGUMENT",
                        "Yêu cầu không hợp lệ. Vui lòng kiểm tra lại dữ liệu đầu vào."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "INVALID_STATE",
                        "Thao tác không thể thực hiện ở trạng thái hiện tại."));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServiceException(AiServiceException ex) {
        int code = ex.getStatusCode();
        HttpStatus status = (code >= 400 && code < 500)
                ? HttpStatus.valueOf(code)
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "AI_SERVICE_ERROR",
                        "Dịch vụ AI gặp sự cố. Vui lòng thử lại sau."));
    }

    @ExceptionHandler(CheckExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleCheckExpired(CheckExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "CHECK_EXPIRED",
                        "Yêu cầu kiểm tra đã hết hạn. Vui lòng liên hệ quản lý để được hỗ trợ."));
    }

    @ExceptionHandler(OtpRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleOtpRateLimit(OtpRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "OTP_RATE_LIMIT",
                        "Bạn đã yêu cầu mã OTP quá nhiều lần. Vui lòng đợi trước khi thử lại."));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiResponse<Void>> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "EMAIL_NOT_VERIFIED",
                        "Email chưa được xác thực. Vui lòng kiểm tra hộp thư và xác thực email của bạn."));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidOtp(InvalidOtpException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(
                        "Invalid or expired OTP",
                        "INVALID_OTP",
                        "Mã OTP không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu mã mới."));
    }

    @ExceptionHandler(PhoneNotVerifiedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePhoneNotVerified(PhoneNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "PHONE_NOT_VERIFIED",
                        "Vui lòng xác thực OTP gửi tới số điện thoại trước khi hoàn tất đăng ký."));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "RESOURCE_NOT_FOUND",
                        "Không tìm thấy dữ liệu yêu cầu."));
    }

    @ExceptionHandler(InvalidInvitationException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidInvitation(InvalidInvitationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        "INVALID_INVITATION",
                        "Lời mời không hợp lệ hoặc đã hết hạn. Vui lòng liên hệ quản trị viên để được cấp lại."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        "Access denied",
                        "ACCESS_DENIED",
                        "Bạn không có quyền thực hiện thao tác này."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.error(
                        ex.getReason(),
                        "REQUEST_ERROR",
                        "Yêu cầu không thể xử lý. Vui lòng kiểm tra lại thông tin."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Request body is missing or malformed",
                        "MALFORMED_REQUEST",
                        "Nội dung yêu cầu không hợp lệ hoặc bị thiếu. Vui lòng kiểm tra định dạng dữ liệu."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(
                        "Unsupported media type",
                        "UNSUPPORTED_MEDIA_TYPE",
                        "Định dạng dữ liệu không được hỗ trợ. Vui lòng sử dụng application/json."));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        // Issue #4 (docs/issues/ISSUES.md): a deleted/nonexistent uploaded file (e.g. an
        // old avatar replaced by a new upload) must 404, not fall through to the generic
        // 500 handler below — this static-resource serving path (WebConfig's /uploads/**
        // mapping) didn't exist before this feature, so nothing had hit this case yet.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(
                        "Resource not found",
                        "RESOURCE_NOT_FOUND",
                        "Không tìm thấy tài nguyên yêu cầu."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "Internal server error",
                        "INTERNAL_ERROR",
                        "Đã có lỗi xảy ra phía máy chủ. Vui lòng thử lại sau hoặc liên hệ hỗ trợ kỹ thuật."));
    }
}
