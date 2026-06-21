package com.fams.modules.auth.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class EmailVerificationService {

    private static final String EMAIL_VERIFY_PREFIX = "email:verify:";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final int tokenTtlHours;

    public EmailVerificationService(StringRedisTemplate redis,
                                    UserRepository userRepository,
                                    @Value("${app.email.verification-ttl-hours:24}") int tokenTtlHours) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.tokenTtlHours = tokenTtlHours;
    }

    public String generateToken(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(EMAIL_VERIFY_PREFIX + token, userId.toString(), tokenTtlHours, TimeUnit.HOURS);
        return token;
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
