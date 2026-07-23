package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.response.RegisterResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.PhoneNotVerifiedException;
import com.fams.shared.security.JwtProvider;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Slf4j
@Service
public class RegisterService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final FirebasePhoneTokenVerifier phoneTokenVerifier;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
    private final String baseUrl;

    public RegisterService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            EmailService emailService,
            EmailVerificationService emailVerificationService,
            FirebasePhoneTokenVerifier phoneTokenVerifier,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays,
            @Value("${app.base-url}") String baseUrl) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.emailVerificationService = emailVerificationService;
        this.phoneTokenVerifier = phoneTokenVerifier;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null;
        String phone = StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null;

        if (email == null && phone == null) {
            throw new IllegalArgumentException("At least one of email or phone is required");
        }

        if (email != null && userRepository.findByEmailAndDeletedAtIsNull(email).isPresent()) {
            throw new DuplicateResourceException("Email is already registered");
        }

        if (phone != null && userRepository.findByPhoneAndDeletedAtIsNull(phone).isPresent()) {
            throw new DuplicateResourceException("Phone number is already registered");
        }

        // Issue #1 (docs/issues/ISSUES.md): phone-only registration used to activate the
        // account immediately without ever checking the phone was real. Now it requires a
        // Firebase phone-auth ID token — proof the client already completed Firebase's OTP
        // flow for this exact number — before the account is created at all.
        boolean phoneVerified = false;
        if (phone != null && email == null) {
            String firebaseIdToken = StringUtils.hasText(request.getFirebaseIdToken())
                    ? request.getFirebaseIdToken().trim() : null;
            if (firebaseIdToken == null) {
                throw new PhoneNotVerifiedException(
                        "Phone registration requires a verified Firebase ID token (firebaseIdToken). "
                                + "Complete the OTP flow with Firebase on the client first.");
            }
            String verifiedPhone = phoneTokenVerifier.verifyAndExtractPhone(firebaseIdToken);
            String normalizedSubmitted = phone.startsWith("+") ? phone : "+" + phone;
            if (!normalizedSubmitted.equals(verifiedPhone)) {
                throw new PhoneNotVerifiedException(
                        "The verified Firebase token is for a different phone number than the one submitted.");
            }
            phoneVerified = true;
        }

        // Phone-only users have no email to verify; email_verified is only meaningful for
        // email-based accounts, so it defaults true here to avoid blocking their (already
        // phone-verified) login.
        boolean emailVerified = (email == null);

        User user = User.builder()
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .isActive(true)
                .emailVerified(emailVerified)
                .phoneVerified(phoneVerified)
                .failedLoginAttempts(0)
                .build();
        userRepository.save(user);
        log.info("New user registered: id={}", user.getId());

        // Email-based registration: send verification email, don't issue tokens yet
        if (email != null) {
            String token = emailVerificationService.generateToken(user.getId());
            String verificationUrl = baseUrl + "/api/v1/auth/verify-email?token=" + token;
            emailService.sendVerificationEmail(email, verificationUrl);
            log.info("Verification email queued for user id={}", user.getId());

            return RegisterResponse.builder()
                    .emailVerificationRequired(true)
                    .message("Registration successful. Please check your email to verify your account before logging in.")
                    .build();
        }

        // Phone-only registration: issue tokens immediately
        String deviceId = StringUtils.hasText(request.getDeviceId()) ? request.getDeviceId() : "unknown";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), null, deviceId, false, null, null);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        String tokenHash = jwtProvider.hashToken(rawRefresh);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        return RegisterResponse.builder()
                .emailVerificationRequired(false)
                .message("Registration successful.")
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
