package com.fams.modules.auth.controller;

import com.fams.modules.auth.dto.request.ChangePasswordRequest;
import com.fams.modules.auth.dto.request.ForgotPasswordRequest;
import com.fams.modules.auth.dto.request.GoogleLoginRequest;
import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.request.LoginTotpRequest;
import com.fams.modules.auth.dto.request.ResetPasswordRequest;
import com.fams.modules.auth.dto.request.TotpVerifyRequest;
import com.fams.modules.auth.dto.request.UpdateProfileRequest;
import com.fams.modules.auth.dto.response.TotpSetupResponse;
import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.dto.request.LogoutRequest;
import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.request.SendOtpRequest;
import com.fams.modules.auth.dto.request.VerifyOtpRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.dto.response.RegisterResponse;
import com.fams.modules.auth.service.EmailVerificationService;
import com.fams.modules.auth.repository.HealthCheckRepository;
import com.fams.modules.auth.service.AuthService;
import com.fams.modules.auth.service.ChangePasswordService;
import com.fams.modules.auth.service.GoogleLoginService;
import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.auth.service.OtpService;
import com.fams.modules.auth.service.LoginTotpService;
import com.fams.modules.auth.service.PasswordResetService;
import com.fams.modules.auth.service.RegisterService;
import com.fams.modules.auth.service.TotpService;
import com.fams.modules.auth.service.UserProfileService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final HealthCheckRepository healthCheckRepository;
    private final AuthService authService;
    private final OtpService otpService;
    private final LogoutService logoutService;
    private final RegisterService registerService;
    private final ChangePasswordService changePasswordService;
    private final UserProfileService userProfileService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final TotpService totpService;
    private final LoginTotpService loginTotpService;
    private final GoogleLoginService googleLoginService;

    public AuthController(HealthCheckRepository healthCheckRepository,
                          AuthService authService,
                          OtpService otpService,
                          LogoutService logoutService,
                          RegisterService registerService,
                          ChangePasswordService changePasswordService,
                          UserProfileService userProfileService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService,
                          TotpService totpService,
                          LoginTotpService loginTotpService,
                          GoogleLoginService googleLoginService) {
        this.healthCheckRepository = healthCheckRepository;
        this.authService = authService;
        this.otpService = otpService;
        this.logoutService = logoutService;
        this.registerService = registerService;
        this.changePasswordService = changePasswordService;
        this.userProfileService = userProfileService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.totpService = totpService;
        this.loginTotpService = loginTotpService;
        this.googleLoginService = googleLoginService;
    }

    @GetMapping("/health")
    public String health() {
        return "FAMS Auth Module is running";
    }

    @GetMapping("/db-health")
    public String databaseHealth() {
        return healthCheckRepository.findAll().get(0).getMessage();
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration attempt for email={} phone={}", request.getEmail(), request.getPhone());
        RegisterResponse response = registerService.register(request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        log.info("Email verification attempt");
        emailVerificationService.verifyToken(token);
        return ResponseEntity.ok(new ApiResponse<>(true, "Email verified successfully. You can now log in.", null));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Password reset requested for email={}", request.getEmail());
        passwordResetService.forgotPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true,
                "If an account with that email exists, a password reset link has been sent.", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Password reset attempt with token");
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Password has been reset successfully.", null));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<Void>> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        log.info("OTP send requested for phone: {}", request.getPhone());
        otpService.sendOtp(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<LoginResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        log.info("OTP verify requested for phone: {}", request.getPhone());
        LoginResponse response = otpService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request,
                                                    HttpServletRequest httpRequest) {
        String authHeader = httpRequest.getHeader("Authorization");
        String rawAccessToken = (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : "";
        log.info("Logout requested");
        logoutService.logout(rawAccessToken, request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Profile fetch for user {}", userDetails.getUserId());
        UserProfileResponse profile = userProfileService.getProfile(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Profile update for user {}", userDetails.getUserId());
        UserProfileResponse profile = userProfileService.updateProfile(userDetails.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Change password requested by user {}", userDetails.getUserId());
        changePasswordService.changePassword(userDetails.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/logout/all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(HttpServletRequest httpRequest,
                                                       @AuthenticationPrincipal FamsUserDetails userDetails) {
        String authHeader = httpRequest.getHeader("Authorization");
        String rawAccessToken = (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : "";
        log.info("Logout-all requested by user {}", userDetails.getUserId());
        logoutService.logoutAll(rawAccessToken, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/login/google")
    public ResponseEntity<ApiResponse<LoginResponse>> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        log.info("Google login attempt");
        LoginResponse response = googleLoginService.loginWithGoogle(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/login/totp")
    public ResponseEntity<ApiResponse<LoginResponse>> loginWithTotp(@Valid @RequestBody LoginTotpRequest request) {
        log.info("TOTP login attempt");
        LoginResponse response = loginTotpService.loginWithTotp(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/totp/setup")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> totpSetup(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP setup initiated by user {}", userDetails.getUserId());
        TotpSetupResponse response = totpService.initiateSetup(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping(value = "/totp/qr", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> totpQrPage(@RequestParam String token) {
        log.info("TOTP QR page requested");
        String html = totpService.buildQrPageHtml(token);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<ApiResponse<Void>> totpVerify(
            @Valid @RequestBody TotpVerifyRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP verify attempt by user {}", userDetails.getUserId());
        totpService.enableTotp(userDetails.getUserId(), request);
        return ResponseEntity.ok(new ApiResponse<>(true, "TOTP two-factor authentication has been enabled.", null));
    }

    @PostMapping("/totp/disable")
    public ResponseEntity<ApiResponse<Void>> totpDisable(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP disable requested by user {}", userDetails.getUserId());
        totpService.disableTotp(userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, "TOTP two-factor authentication has been disabled.", null));
    }
}
