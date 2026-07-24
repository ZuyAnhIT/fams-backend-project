package com.fams.modules.auth.service;

import com.fams.modules.auth.entity.PhoneOtp;
import com.fams.modules.auth.repository.PhoneOtpRepository;
import com.fams.modules.auth.util.PhoneNumbers;
import com.fams.shared.exception.OtpRateLimitException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * Send/verify mechanics for the in-house SMS OTP (dev mode logs the code instead of
 * sending a real SMS — see {@link SmsService}). Shared by every flow that needs proof
 * of phone ownership: registration ({@code purpose=REGISTER}) and profile phone change
 * ({@code purpose=PROFILE_PHONE_CHANGE}) as of now. Each caller owns its own business
 * rules (e.g. "phone must not already be registered") — this class only owns the OTP
 * lifecycle itself, identically for every purpose.
 */
@Slf4j
@Service
public class PhoneOtpService {

    private static final int OTP_TTL_MINUTES = 5;
    private static final int OTP_SEND_MAX = 3;
    private static final int OTP_SEND_WINDOW_MINUTES = 15;
    private static final int OTP_MAX_ATTEMPTS = 5;

    private final PhoneOtpRepository phoneOtpRepository;
    private final SmsService smsService;

    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository, SmsService smsService) {
        this.phoneOtpRepository = phoneOtpRepository;
        this.smsService = smsService;
    }

    @Transactional
    public void sendOtp(String phone, String purpose) {
        OffsetDateTime windowStart = OffsetDateTime.now().minusMinutes(OTP_SEND_WINDOW_MINUTES);
        long recentCount = phoneOtpRepository.countRecentByPhone(phone, purpose, windowStart);
        if (recentCount >= OTP_SEND_MAX) {
            log.warn("OTP rate limit exceeded for phone={} purpose={} ({} requests in last {} min)",
                    PhoneNumbers.mask(phone), purpose, recentCount, OTP_SEND_WINDOW_MINUTES);
            throw new OtpRateLimitException();
        }

        phoneOtpRepository.invalidateAllActive(phone, purpose, OffsetDateTime.now());

        String otpCode = generateOtp();
        PhoneOtp otp = PhoneOtp.builder()
                .phone(phone)
                .otpCode(otpCode)
                .purpose(purpose)
                .expiresAt(OffsetDateTime.now().plusMinutes(OTP_TTL_MINUTES))
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .build();
        phoneOtpRepository.save(otp);

        smsService.sendOtp(phone, otpCode);
        log.info("OTP sent to phone={} purpose={}", PhoneNumbers.mask(phone), purpose);
    }

    @Transactional
    public void verifyOtp(String phone, String purpose, String inputCode) {
        PhoneOtp otp = phoneOtpRepository
                .findValidOtp(phone, purpose, OffsetDateTime.now())
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
            throw new IllegalArgumentException("Mã OTP không đúng. Còn " + remaining + " lần thử.");
        }

        otp.setUsedAt(OffsetDateTime.now());
        phoneOtpRepository.save(otp);
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }
}
