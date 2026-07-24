package com.fams.modules.auth.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class EmailVerificationService {

    private static final String EMAIL_VERIFY_PREFIX = "email:verify:";
    private static final String RESEND_RATE_PREFIX = "email:verify:resend:rate:";
    private static final String EMAIL_CHANGE_PREFIX = "email:change:";
    private static final int RATE_WINDOW_MINUTES = 10;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final int tokenTtlHours;
    private final String frontendUrl;
    private final int resendRateLimitMax;

    public EmailVerificationService(StringRedisTemplate redis,
                                    UserRepository userRepository,
                                    EmailService emailService,
                                    @Value("${app.email.verification-ttl-hours:24}") int tokenTtlHours,
                                    @Value("${app.frontend-url}") String frontendUrl,
                                    @Value("${app.email.resend-rate-limit-max:3}") int resendRateLimitMax) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.tokenTtlHours = tokenTtlHours;
        this.frontendUrl = frontendUrl;
        this.resendRateLimitMax = resendRateLimitMax;
    }

    public String generateToken(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(EMAIL_VERIFY_PREFIX + token, userId.toString(), tokenTtlHours, TimeUnit.HOURS);
        return token;
    }

    /**
     * Issue #2 (docs/issues/ISSUES.md): registration had no way to re-send the
     * verification email if the user missed it or the link expired. Mirrors
     * {@link PasswordResetService#forgotPassword} — silent no-op for unknown/already-
     * verified/phone-only accounts and rate-limited per email, so this endpoint can't be
     * used to enumerate registered emails or spam a mailbox.
     */
    public void resendVerification(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        String rateKey = RESEND_RATE_PREFIX + normalizedEmail;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, RATE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count > resendRateLimitMax) {
            log.warn("Email verification resend rate limit hit for email={}", normalizedEmail);
            return;
        }

        userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail).ifPresentOrElse(user -> {
            if (user.isEmailVerified()) {
                log.debug("Resend verification requested for already-verified email — silently ignored");
                return;
            }
            String token = generateToken(user.getId());
            String verificationUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                    .path("/verify-email")
                    .queryParam("token", token)
                    .build()
                    .encode()
                    .toUriString();
            emailService.sendVerificationEmail(normalizedEmail, verificationUrl);
            log.info("Verification email re-sent for user id={}", user.getId());
        }, () -> log.debug("Resend verification requested for unknown email — silently ignored"));
    }

    /**
     * Profile "add/change email" (step 1): unlike {@link #generateToken}, this does NOT
     * touch {@code users.email} yet — the pending {@code userId|newEmail} pair only lives
     * in Redis until the link is clicked. This means an existing verified login channel
     * (e.g. the phone the user registered with) is never disrupted while the new email is
     * still unconfirmed, unlike the registration flow where the email is the only channel
     * and so has nothing to disrupt.
     */
    public String generateChangeToken(UUID userId, String newEmail) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(EMAIL_CHANGE_PREFIX + token, userId + "|" + newEmail, tokenTtlHours, TimeUnit.HOURS);
        return token;
    }

    /**
     * Profile "add/change email" (step 2): applies the pending email now that its owner
     * proved control of the inbox. Re-checks the uniqueness constraint at commit time (not
     * just at request time) — the link's TTL is hours long, long enough for a race with
     * another user claiming the same address in between.
     */
    @Transactional
    public void confirmEmailChange(String token) {
        String key = EMAIL_CHANGE_PREFIX + token;
        String value = redis.opsForValue().get(key);
        if (value == null) {
            throw new IllegalArgumentException("Invalid or expired verification token");
        }

        String[] parts = value.split("\\|", 2);
        UUID userId = UUID.fromString(parts[0]);
        String newEmail = parts[1];

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        userRepository.findByEmailAndDeletedAtIsNull(newEmail).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("Email address is already in use");
            }
        });

        user.setEmail(newEmail);
        user.setEmailVerified(true);
        userRepository.save(user);
        redis.delete(key);
        log.info("Email changed and verified for user id={}", userId);
    }

    @Transactional
    public void verifyToken(String token) {
        String key = EMAIL_VERIFY_PREFIX + token;
        String userIdStr = redis.opsForValue().get(key);
        if (userIdStr == null) {
            throw new IllegalArgumentException("Invalid or expired verification token");
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setEmailVerified(true);
        userRepository.save(user);
        redis.delete(key);
        log.info("Email verified for user id={}", userId);
    }
}
