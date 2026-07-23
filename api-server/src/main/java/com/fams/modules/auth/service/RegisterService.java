package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.request.SendOtpRequest;
import com.fams.modules.auth.dto.response.RegisterResponse;
import com.fams.modules.auth.entity.PhoneOtp;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.PhoneOtpRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.OtpRateLimitException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

@Slf4j
@Service
public class RegisterService {

    // OTP hết hạn sau 5 phút
    private static final int OTP_TTL_MINUTES = 5;
    // Tối đa 3 lần gửi OTP trong 15 phút
    private static final int OTP_SEND_MAX = 3;
    private static final int OTP_SEND_WINDOW_MINUTES = 15;
    // Tối đa 5 lần nhập sai OTP
    private static final int OTP_MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PhoneOtpRepository phoneOtpRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final SmsService smsService;
    private final String baseUrl;

    public RegisterService(
            UserRepository userRepository,
            PhoneOtpRepository phoneOtpRepository,
            BCryptPasswordEncoder passwordEncoder,
            EmailService emailService,
            EmailVerificationService emailVerificationService,
            SmsService smsService,
            @Value("${app.base-url}") String baseUrl) {
        this.userRepository = userRepository;
        this.phoneOtpRepository = phoneOtpRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.emailVerificationService = emailVerificationService;
        this.smsService = smsService;
        this.baseUrl = baseUrl;
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 1 (Phone flow): Gửi OTP về số điện thoại
    // POST /api/v1/auth/register/send-otp
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void sendRegistrationOtp(SendOtpRequest request) {
        String phone = normalizePhone(request.getPhone());

        // Kiểm tra phone đã có tài khoản active chưa
        userRepository.findByPhoneAndDeletedAtIsNull(phone).ifPresent(existing -> {
            // Chỉ block nếu phone đã verified, còn chưa verify thì cho gửi lại
            if (existing.isPhoneVerified()) {
                throw new DuplicateResourceException("Số điện thoại này đã được đăng ký");
            }
        });

        // Rate-limit: tối đa OTP_SEND_MAX lần trong OTP_SEND_WINDOW_MINUTES phút
        OffsetDateTime windowStart = OffsetDateTime.now().minusMinutes(OTP_SEND_WINDOW_MINUTES);
        long recentCount = phoneOtpRepository.countRecentByPhone(phone, "REGISTER", windowStart);
        if (recentCount >= OTP_SEND_MAX) {
            // OtpRateLimitException không nhận tham số — message cố định lấy từ
            // GlobalExceptionHandler (mapped -> 429 TOO_MANY_REQUESTS).
            // Log chi tiết ở đây để dev có thể tra cứu nếu cần.
            log.warn("OTP rate limit exceeded for phone={} ({} requests in last {} min)",
                maskPhone(phone), recentCount, OTP_SEND_WINDOW_MINUTES);
            throw new OtpRateLimitException();
        }

        // Huỷ OTP cũ chưa dùng
        phoneOtpRepository.invalidateAllActive(phone, "REGISTER", OffsetDateTime.now());

        // Sinh OTP 6 số
        String otpCode = generateOtp();

        PhoneOtp otp = PhoneOtp.builder()
                .phone(phone)
                .otpCode(otpCode)
                .purpose("REGISTER")
                .expiresAt(OffsetDateTime.now().plusMinutes(OTP_TTL_MINUTES))
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .build();
        phoneOtpRepository.save(otp);

        // Gửi OTP qua SMS (Firebase / SMS provider)
        smsService.sendOtp(phone, otpCode);

        log.info("Registration OTP sent to phone={}", maskPhone(phone));
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 2: Đăng ký tài khoản
    // POST /api/v1/auth/register
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = StringUtils.hasText(request.getEmail()) ? request.getEmail().trim().toLowerCase() : null;
        String phone = StringUtils.hasText(request.getPhone()) ? normalizePhone(request.getPhone()) : null;

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
            verifyPhoneOtp(phone, request.getOtpCode());
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
            user.getId(), maskEmail(email), maskPhone(phone));

        // ── Email flow: gửi link xác thực ────────────────────────────────────
        if (email != null) {
            String token = emailVerificationService.generateToken(user.getId());
            String verificationUrl = baseUrl + "/api/v1/auth/verify-email?token=" + token;
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

    /**
     * Xác minh OTP: tìm OTP hợp lệ, kiểm tra mã, đánh dấu đã dùng.
     */
    private void verifyPhoneOtp(String phone, String inputCode) {
        PhoneOtp otp = phoneOtpRepository
                .findValidOtp(phone, "REGISTER", OffsetDateTime.now())
                .orElseThrow(() -> new IllegalArgumentException(
                    "Mã OTP không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu mã mới."));

        if (!otp.getOtpCode().equals(inputCode)) {
            otp.setAttempts(otp.getAttempts() + 1);
            phoneOtpRepository.save(otp);

            int remaining = OTP_MAX_ATTEMPTS - otp.getAttempts();
            if (remaining <= 0) {
                throw new IllegalArgumentException(
                    "Bạn đã nhập sai OTP quá nhiều lần. Vui lòng yêu cầu mã mới.");
            }
            throw new IllegalArgumentException(
                "Mã OTP không đúng. Còn " + remaining + " lần thử.");
        }

        // OTP đúng → đánh dấu đã dùng
        otp.setUsedAt(OffsetDateTime.now());
        phoneOtpRepository.save(otp);
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000); // 6 chữ số
        return String.valueOf(code);
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String cleaned = phone.trim();
        // Chuẩn hoá: 0912... → +84912...
        if (cleaned.startsWith("0")) {
            cleaned = "+84" + cleaned.substring(1);
        } else if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.length() <= 4) return "****";
        return phone.substring(0, phone.length() - 4) + "****";
    }
}