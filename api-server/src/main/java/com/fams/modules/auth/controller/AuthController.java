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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Auth", description = "Authentication, registration, profile and 2FA endpoints")
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

    @Operation(summary = "Health check", description = "Returns a simple liveness string. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Service is up")
    })
    @SecurityRequirements({})
    @GetMapping("/health")
    public String health() {
        return "FAMS Auth Module is running";
    }

    @Operation(summary = "DB health check", description = "Queries the database to verify connectivity. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Database is reachable"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Database unreachable")
    })
    @SecurityRequirements({})
    @GetMapping("/db-health")
    public String databaseHealth() {
        return healthCheckRepository.findAll().get(0).getMessage();
    }

    @Operation(summary = "Register a new account",
        description = "Creates a user account with email+password or phone. Sends a verification email when an email is provided.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created",
            content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email or phone already registered")
    })
    @SecurityRequirements({})
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration attempt for email={} phone={}", request.getEmail(), request.getPhone());
        RegisterResponse response = registerService.register(request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "Verify email address",
        description = "Activates an account using the token sent in the verification email. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email verified successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Token is missing, invalid, or expired")
    })
    @SecurityRequirements({})
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        log.info("Email verification attempt");
        emailVerificationService.verifyToken(token);
        return ResponseEntity.ok(new ApiResponse<>(true, "Email verified successfully. You can now log in.", null));
    }

    @Operation(summary = "Request password reset",
        description = "Sends a password-reset link to the given email if an account exists. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reset email dispatched (or silently ignored if email not found)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @SecurityRequirements({})
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Password reset requested for email={}", request.getEmail());
        passwordResetService.forgotPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true,
                "If an account with that email exists, a password reset link has been sent.", null));
    }

    @Operation(summary = "Reset password with token",
        description = "Sets a new password using the token received by email. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password reset successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or token invalid/expired")
    })
    @SecurityRequirements({})
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Password reset attempt with token");
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Password has been reset successfully.", null));
    }

    @Operation(summary = "Login with email and password",
        description = "Returns JWT access + refresh tokens. When TOTP is enabled, returns a pendingToken instead and sets totpRequired=true. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Bad credentials"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Account temporarily locked due to too many failed attempts")
    })
    @SecurityRequirements({})
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Send OTP to phone",
        description = "Sends a 6-digit OTP to the provided phone number for phone-based login. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP sent"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @SecurityRequirements({})
    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<Void>> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        log.info("OTP send requested for phone: {}", request.getPhone());
        otpService.sendOtp(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Verify OTP and log in",
        description = "Validates the OTP code and returns JWT tokens on success. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP verified — tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or expired OTP")
    })
    @SecurityRequirements({})
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<LoginResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        log.info("OTP verify requested for phone: {}", request.getPhone());
        LoginResponse response = otpService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Logout from current device",
        description = "Invalidates the current access token and refresh token. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logged out successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
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

    @Operation(summary = "Get current user profile",
        description = "Returns the authenticated user's profile information.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile returned",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Profile fetch for user {}", userDetails.getUserId());
        UserProfileResponse profile = userProfileService.getProfile(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @Operation(summary = "Update current user profile",
        description = "Updates display name, phone, and/or avatar URL for the authenticated user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile updated",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Profile update for user {}", userDetails.getUserId());
        UserProfileResponse profile = userProfileService.updateProfile(userDetails.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @Operation(summary = "Change password",
        description = "Changes the authenticated user's password. Current password must be provided for verification.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password changed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or current password incorrect"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Change password requested by user {}", userDetails.getUserId());
        changePasswordService.changePassword(userDetails.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Logout from all devices",
        description = "Invalidates all active sessions for the authenticated user across every device.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "All sessions terminated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
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

    @Operation(summary = "Login with Google",
        description = "Exchanges a Google ID token for FAMS JWT tokens. Creates an account on first use. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid Google ID token")
    })
    @SecurityRequirements({})
    @PostMapping("/login/google")
    public ResponseEntity<ApiResponse<LoginResponse>> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        log.info("Google login attempt");
        LoginResponse response = googleLoginService.loginWithGoogle(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Complete login with TOTP code",
        description = "Exchanges the pendingToken (from the initial login response) and a 6-digit TOTP code for real JWT tokens. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP verified — JWT tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or expired pending token / wrong TOTP code")
    })
    @SecurityRequirements({})
    @PostMapping("/login/totp")
    public ResponseEntity<ApiResponse<LoginResponse>> loginWithTotp(@Valid @RequestBody LoginTotpRequest request) {
        log.info("TOTP login attempt");
        LoginResponse response = loginTotpService.loginWithTotp(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Initiate TOTP 2FA setup",
        description = "Returns a setup token and QR code URL to configure an Authenticator app. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP setup initiated",
            content = @Content(schema = @Schema(implementation = TotpSetupResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "TOTP is already enabled for this account")
    })
    @PostMapping("/totp/setup")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> totpSetup(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP setup initiated by user {}", userDetails.getUserId());
        TotpSetupResponse response = totpService.initiateSetup(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "TOTP QR code page",
        description = "Returns an HTML page containing the QR code for TOTP setup. Identified by setupToken query parameter. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "QR code HTML page"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing or invalid token")
    })
    @SecurityRequirements({})
    @GetMapping(value = "/totp/qr", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> totpQrPage(@RequestParam String token) {
        log.info("TOTP QR page requested");
        String html = totpService.buildQrPageHtml(token);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @Operation(summary = "Confirm and enable TOTP",
        description = "Verifies the first TOTP code from the Authenticator app and activates 2FA on the account. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP enabled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid setup token or wrong TOTP code"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/totp/verify")
    public ResponseEntity<ApiResponse<Void>> totpVerify(
            @Valid @RequestBody TotpVerifyRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP verify attempt by user {}", userDetails.getUserId());
        totpService.enableTotp(userDetails.getUserId(), request);
        return ResponseEntity.ok(new ApiResponse<>(true, "TOTP two-factor authentication has been enabled.", null));
    }

    @Operation(summary = "Disable TOTP 2FA",
        description = "Removes TOTP two-factor authentication from the authenticated user's account. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP disabled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "TOTP is not enabled on this account")
    })
    @PostMapping("/totp/disable")
    public ResponseEntity<ApiResponse<Void>> totpDisable(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP disable requested by user {}", userDetails.getUserId());
        totpService.disableTotp(userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, "TOTP two-factor authentication has been disabled.", null));
    }
}
