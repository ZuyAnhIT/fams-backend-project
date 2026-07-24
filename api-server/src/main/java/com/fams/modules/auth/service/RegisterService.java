package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.request.SendOtpRequest;
import com.fams.modules.auth.dto.response.RegisterResponse;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.util.PhoneNumbers;
import com.fams.shared.exception.DuplicateResourceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
public class RegisterService {

    private static final String OTP_PURPOSE = "REGISTER";

    private final UserRepository userRepository;
    private final PhoneOtpService phoneOtpService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final String frontendUrl;

    public RegisterService(
            UserRepository userRepository,
            PhoneOtpService phoneOtpService,
            BCryptPasswordEncoder passwordEncoder,
            EmailService emailService,
            EmailVerificationService emailVerificationService,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.userRepository = userRepository;
        this.phoneOtpService = phoneOtpService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.emailVerificationService = emailVerificationService;
        this.frontendUrl = frontendUrl;
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 1 (Phone flow): Gửi OTP về số điện thoại
    // POST /api/v1/auth/register/send-otp
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void sendRegistrationOtp(SendOtpRequest request) {
        String phone = PhoneNumbers.normalize(request.getPhone());

        // Kiểm tra phone đã có tài khoản active chưa
        userRepository.findByPhoneAndDeletedAtIsNull(phone).ifPresent(existing -> {
            // Chỉ block nếu phone đã verified, còn chưa verify thì cho gửi lại
            if (existing.isPhoneVerified()) {
                throw new DuplicateResourceException("Số điện thoại này đã được đăng ký");
            }
        });

        phoneOtpService.sendOtp(phone, OTP_PURPOSE);
        log.info("Registration OTP sent to phone={}", PhoneNumbers.mask(phone));
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 2: Đăng ký tài khoản
    // POST /api/v1/auth/register
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = StringUtils.hasText(request.getEmail()) ? request.getEmail().trim().toLowerCase() : null;
        String phone = StringUtils.hasText(request.getPhone()) ? PhoneNumbers.normalize(request.getPhone()) : null;

        // ── Validate: phải có ít nhất email hoặc phone ───────────────────────
        if (email == null && phone == null) {
            throw new IllegalArgumentException("Vui lòng cung cấp email hoặc số điện thoại để đăng ký");
        }

        // ── Kiểm tra trùng email ─────────────────────────────────────────────
        if (email != null) {
            userRepository.findByEmailAndDeletedAtIsNull(email).ifPresent(existing -> {
                // Email đã register (kể cả chưa verify) → không cho register lại
                // User phải dùng "Gửi lại email xác thực" nếu chưa verify
                throw new DuplicateResourceException(
                    "Email này đã được đăng ký. Nếu chưa xác thực, vui lòng kiểm tra hộp thư hoặc dùng chức năng gửi lại email xác thực.");
            });
        }

        // ── Kiểm tra trùng phone ─────────────────────────────────────────────
        if (phone != null) {
            userRepository.findByPhoneAndDeletedAtIsNull(phone).ifPresent(existing -> {
                if (existing.isPhoneVerified()) {
                    throw new DuplicateResourceException("Số điện thoại này đã được đăng ký");
                }
                // Phone chưa verify: xoá user cũ để cho đăng ký lại
                // (trường hợp user bỏ dở flow, không xác thực OTP)
                userRepository.delete(existing);
                log.info("Deleted unverified phone user id={} to allow re-registration", existing.getId());
            });
        }

        // ── Xác thực OTP nếu đăng ký bằng phone ────────────────────────────
        boolean phoneVerified = false;
        if (phone != null && email == null) {
            // Phone-only: bắt buộc phải có OTP
            if (!StringUtils.hasText(request.getOtpCode())) {
                throw new IllegalArgumentException(
                    "Vui lòng nhập mã OTP đã được gửi đến số điện thoại của bạn");
            }
            phoneOtpService.verifyOtp(phone, OTP_PURPOSE, request.getOtpCode());
            phoneVerified = true;
        }

        // ── Tạo user ─────────────────────────────────────────────────────────
        boolean emailVerified = (email == null); // phone-only user không cần verify email

        User user = User.builder()
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName().trim())
                .isActive(true)
                .emailVerified(emailVerified)
                .phoneVerified(phoneVerified)
                .failedLoginAttempts(0)
                .build();
        userRepository.save(user);
        log.info("New user registered: id={} email={} phone={}",
            user.getId(), maskEmail(email), PhoneNumbers.mask(phone));

        // ── Email flow: gửi link xác thực ────────────────────────────────────
        if (email != null) {
            String token = emailVerificationService.generateToken(user.getId());
            String verificationUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                    .path("/verify-email")
                    .queryParam("token", token)
                    .build()
                    .encode()
                    .toUriString();
            emailService.sendVerificationEmail(email, verificationUrl);
            log.info("Verification email queued for user id={}", user.getId());

            return RegisterResponse.builder()
                    .userId(user.getId())
                    .emailVerificationRequired(true)
                    .phoneVerified(false)
                    .message("Đăng ký thành công! Vui lòng kiểm tra email " + maskEmail(email)
                        + " để xác thực tài khoản trước khi đăng nhập.")
                    .build();
        }

        // ── Phone flow: đã verify OTP → trả về thành công ────────────────────
        return RegisterResponse.builder()
                .userId(user.getId())
                .emailVerificationRequired(false)
                .phoneVerified(true)
                .message("Đăng ký thành công! Bạn có thể đăng nhập ngay bây giờ.")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════════

    private String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}