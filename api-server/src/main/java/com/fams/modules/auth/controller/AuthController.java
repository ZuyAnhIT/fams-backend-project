package com.fams.modules.auth.controller;

import com.fams.modules.auth.dto.request.ChangePasswordRequest;
import com.fams.modules.auth.dto.request.DisableTotpRequest;
import com.fams.modules.auth.dto.request.FirebasePhoneLoginRequest;
import com.fams.modules.auth.dto.request.ForgotPasswordRequest;
import com.fams.modules.auth.dto.request.GoogleLoginRequest;
import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.request.LoginTotpRequest;
import com.fams.modules.auth.dto.request.RefreshTokenRequest;
import com.fams.modules.auth.dto.request.ResendVerificationRequest;
import com.fams.modules.auth.dto.request.ResetPasswordRequest;
import com.fams.modules.auth.dto.request.TotpVerifyRequest;
import com.fams.modules.auth.dto.request.UpdateProfileRequest;
import com.fams.modules.auth.dto.response.TotpEnableResponse;
import com.fams.modules.auth.dto.response.TotpSetupResponse;
import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.dto.request.LogoutRequest;
import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.dto.response.RegisterResponse;
import com.fams.modules.auth.dto.response.SessionResponse;
import com.fams.modules.auth.service.EmailVerificationService;
import com.fams.modules.auth.repository.HealthCheckRepository;
import com.fams.modules.auth.service.AuthService;
import com.fams.modules.auth.service.ChangePasswordService;
import com.fams.modules.auth.service.FirebasePhoneLoginService;
import com.fams.modules.auth.service.GoogleLoginService;
import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.auth.service.LoginTotpService;
import com.fams.modules.auth.service.PasswordResetService;
import com.fams.modules.auth.service.RefreshTokenService;
import com.fams.modules.auth.service.RegisterService;
import com.fams.modules.auth.service.TotpService;
import com.fams.modules.auth.service.UserProfileService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.util.UUID;

@Slf4j
@Tag(name = "Auth", description = "Authentication, registration, profile and 2FA endpoints")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final HealthCheckRepository healthCheckRepository;
    private final AuthService authService;
    private final FirebasePhoneLoginService firebasePhoneLoginService;
    private final LogoutService logoutService;
    private final RegisterService registerService;
    private final ChangePasswordService changePasswordService;
    private final UserProfileService userProfileService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final TotpService totpService;
    private final LoginTotpService loginTotpService;
    private final GoogleLoginService googleLoginService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(HealthCheckRepository healthCheckRepository,
                          AuthService authService,
                          FirebasePhoneLoginService firebasePhoneLoginService,
                          LogoutService logoutService,
                          RegisterService registerService,
                          ChangePasswordService changePasswordService,
                          UserProfileService userProfileService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService,
                          TotpService totpService,
                          LoginTotpService loginTotpService,
                          GoogleLoginService googleLoginService,
                          RefreshTokenService refreshTokenService) {
        this.healthCheckRepository = healthCheckRepository;
        this.authService = authService;
        this.firebasePhoneLoginService = firebasePhoneLoginService;
        this.logoutService = logoutService;
        this.registerService = registerService;
        this.changePasswordService = changePasswordService;
        this.userProfileService = userProfileService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.totpService = totpService;
        this.loginTotpService = loginTotpService;
        this.googleLoginService = googleLoginService;
        this.refreshTokenService = refreshTokenService;
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
        description = "Creates a user account with email+password or phone. Sends a verification email when an "
            + "email is provided. Phone-only registration requires `firebaseIdToken`: the client must complete "
            + "Firebase's phone OTP flow first and pass the resulting ID token to prove the phone number is real.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created",
            content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error, " +
            "or (PHONE_NOT_VERIFIED) phone registration missing/mismatched firebaseIdToken"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Firebase ID token invalid or expired (INVALID_OTP)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email or phone already registered"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Firebase not configured on the server")
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

    @Operation(summary = "Resend the email verification link",
        description = "Issue #2 (docs/issues/ISSUES.md): re-sends the verification email if the original was "
            + "missed or expired. Always responds 200 regardless of whether the email exists, is already "
            + "verified, or is rate-limited — this endpoint must not be usable to enumerate registered emails. "
            + "No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Request accepted (email sent if applicable — response does not reveal which)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error")
    })
    @SecurityRequirements({})
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        log.info("Resend verification requested for email={}", request.getEmail());
        emailVerificationService.resendVerification(request.getEmail());
        return ResponseEntity.ok(new ApiResponse<>(true,
                "If an account with that email exists and is not yet verified, a new verification email has been sent.", null));
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

    @Operation(
        summary = "Switch active company (multi-tenant users)",
        description = "Issue #3 (docs/issues/ISSUES.md): a user belonging to several companies can switch which " +
            "one their session currently operates as. Previously there was no way to do this — login and every " +
            "token refresh always defaulted to the oldest role assignment. Requires an active role in the " +
            "target tenant; persists the switch so it survives subsequent token refreshes.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Switched — new tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid/expired refresh token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "No active role in the target company"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping("/switch-tenant")
    public ResponseEntity<ApiResponse<LoginResponse>> switchTenant(
            @Valid @RequestBody com.fams.modules.auth.dto.request.SwitchTenantRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Switch-tenant requested by user {} -> tenant {}", userDetails.getUserId(), request.getTenantId());
        LoginResponse response = authService.switchTenant(
                userDetails.getUserId(), request.getTenantId(), request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Login with Firebase Phone Auth",
        description = "Accepts a Firebase ID Token obtained after the client completes phone number verification " +
                      "via Firebase Authentication (SMS OTP). The backend verifies the token with Firebase, " +
                      "extracts the phone number, and returns FAMS JWT tokens. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful — JWT tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — firebaseIdToken field missing"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Firebase token invalid/expired, or phone not linked to a FAMS account"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Firebase is not configured on this server")
    })
    @SecurityRequirements({})
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<LoginResponse>> loginWithPhone(
            @Valid @RequestBody FirebasePhoneLoginRequest request) {
        log.info("Firebase phone login attempt");
        LoginResponse response = firebasePhoneLoginService.loginWithPhone(request);
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
        description = "Updates display name, phone, avatar URL, date of birth, hometown, gender, and/or address "
            + "for the authenticated user (Issue #4, docs/issues/ISSUES.md). To upload an actual image file "
            + "instead of linking an existing URL, use POST /auth/profile/avatar.")
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

    @Operation(summary = "Upload avatar image",
        description = "Issue #4 (docs/issues/ISSUES.md): uploads an image file (JPEG/PNG/WEBP, max 5MB) from the "
            + "user's device as their avatar, replacing any previous uploaded avatar. Stored in S3-compatible "
            + "object storage (MinIO in dev, real AWS S3 in production).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Avatar uploaded",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing file, wrong type, or too large"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping(value = "/profile/avatar", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UserProfileResponse>> uploadAvatar(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Avatar upload for user {}", userDetails.getUserId());
        UserProfileResponse profile = userProfileService.updateAvatarFile(userDetails.getUserId(), file);
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

    @Operation(summary = "List active sessions/devices",
        description = "Issue #6 (docs/issues/ISSUES.md): lists every active login session for the authenticated "
            + "user — device, IP/user-agent captured at login, and when it was last active. The entry matching "
            + "the request's own session is flagged `current`. This endpoint did not exist before.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sessions listed",
            content = @Content(schema = @Schema(implementation = SessionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<java.util.List<SessionResponse>>> listSessions(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List sessions requested by user {}", userDetails.getUserId());
        var sessions = logoutService.listSessions(userDetails.getUserId(), userDetails.getDeviceId());
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    @Operation(summary = "Log out a specific session/device",
        description = "Issue #6 (docs/issues/ISSUES.md): revokes exactly one session by its ID (from "
            + "GET /auth/sessions) — e.g. \"log out my old phone\" while sitting at a different device. "
            + "Previously impossible: the only logout endpoint required already holding that device's raw "
            + "refresh token, which a different device never has.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Session revoked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Session not found, already revoked, or belongs to a different user")
    })
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> logoutSession(
            @Parameter(description = "Session (refresh token) UUID") @PathVariable UUID sessionId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Logout session {} requested by user {}", sessionId, userDetails.getUserId());
        logoutService.logoutSession(userDetails.getUserId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Log out all OTHER devices",
        description = "Issue #6 (docs/issues/ISSUES.md): revokes every active session EXCEPT the one making this "
            + "request — the classic \"sign out everywhere else\" option. POST /auth/logout/all revokes the "
            + "caller's own session too with no way to opt out; this is the alternative that keeps the current "
            + "device logged in.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Other sessions terminated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/logout/others")
    public ResponseEntity<ApiResponse<Void>> logoutOthers(@AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Logout-others requested by user {}", userDetails.getUserId());
        logoutService.logoutAllExceptCurrent(userDetails.getUserId(), userDetails.getDeviceId());
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

    @Operation(summary = "Link a Google account",
        description = "Issue #7 (docs/issues/ISSUES.md): while already logged in (e.g. via email+password), "
            + "explicitly connects a Google account for one-click login next time — rather than waiting for "
            + "the implicit auto-link that already happens the next time POST /auth/login/google is called "
            + "with a matching email. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Google account linked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized, or invalid Google ID token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "This Google account is already linked to a different user")
    })
    @PostMapping("/link-google")
    public ResponseEntity<ApiResponse<Void>> linkGoogle(@Valid @RequestBody GoogleLoginRequest request,
                                                        @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Link Google account requested by user {}", userDetails.getUserId());
        googleLoginService.linkGoogleAccount(userDetails.getUserId(), request.getIdToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Unlink Google account",
        description = "Issue #7 (docs/issues/ISSUES.md): disconnects Google sign-in from the account. Requires "
            + "a password to already be set (via Change Password or Forgot Password) — otherwise the account "
            + "would have no way to log in on demand. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Google account unlinked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "No password set on this account yet"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/unlink-google")
    public ResponseEntity<ApiResponse<Void>> unlinkGoogle(@AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Unlink Google account requested by user {}", userDetails.getUserId());
        googleLoginService.unlinkGoogleAccount(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Complete login with TOTP code",
        description = "Exchanges the pendingToken (from the initial login response) and either a 6-digit TOTP "
            + "code or a one-time backupCode (Issue #5, docs/issues/ISSUES.md — for when the authenticator "
            + "device is lost) for real JWT tokens. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP verified — JWT tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or expired pending token / wrong TOTP or backup code")
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
        description = "Verifies the first TOTP code from the Authenticator app and activates 2FA on the account. "
            + "Returns one-time backup codes (Issue #5, docs/issues/ISSUES.md) — shown only this once, save them "
            + "somewhere safe. Requires Bearer token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP enabled successfully — backup codes returned",
            content = @Content(schema = @Schema(implementation = TotpEnableResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid setup token or wrong TOTP code"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/totp/verify")
    public ResponseEntity<ApiResponse<TotpEnableResponse>> totpVerify(
            @Valid @RequestBody TotpVerifyRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP verify attempt by user {}", userDetails.getUserId());
        TotpEnableResponse response = totpService.enableTotp(userDetails.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Disable TOTP 2FA",
        description = "Issue #5 (docs/issues/ISSUES.md): removes TOTP two-factor authentication from the "
            + "authenticated user's account. Requires proving continued control of the account — provide "
            + "exactly one of `password`, `code`, or `backupCode` in the request body. A valid Bearer token "
            + "alone is no longer sufficient (previously it was, meaning a stolen session token could disable "
            + "2FA with no further proof).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP disabled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "TOTP is not enabled on this account"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized, or re-authentication (password/code/backupCode) failed")
    })
    @PostMapping("/totp/disable")
    public ResponseEntity<ApiResponse<Void>> totpDisable(
            @RequestBody DisableTotpRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("TOTP disable requested by user {}", userDetails.getUserId());
        totpService.disableTotp(userDetails.getUserId(), request);
        return ResponseEntity.ok(new ApiResponse<>(true, "TOTP two-factor authentication has been disabled.", null));
    }

    @Operation(summary = "Refresh access token",
        description = "Exchanges a valid refresh token for a new access token and a rotated refresh token. No auth required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refreshed successfully",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — refreshToken field missing"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh token invalid, revoked, or expired"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User associated with this token no longer exists")
    })
    @SecurityRequirements({})
    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<LoginResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        log.info("Refresh token requested");
        LoginResponse response = refreshTokenService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(new ApiResponse<>(true, "Token refreshed successfully", response));
    }
}
