package com.fams.modules.auth.controller;

import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.request.LogoutRequest;
import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.request.SendOtpRequest;
import com.fams.modules.auth.dto.request.VerifyOtpRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.repository.HealthCheckRepository;
import com.fams.modules.auth.service.AuthService;
import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.auth.service.OtpService;
import com.fams.modules.auth.service.RegisterService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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

    public AuthController(HealthCheckRepository healthCheckRepository,
                          AuthService authService,
                          OtpService otpService,
                          LogoutService logoutService,
                          RegisterService registerService) {
        this.healthCheckRepository = healthCheckRepository;
        this.authService = authService;
        this.otpService = otpService;
        this.logoutService = logoutService;
        this.registerService = registerService;
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
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration attempt for email={} phone={}", request.getEmail(), request.getPhone());
        LoginResponse response = registerService.register(request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
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
}
