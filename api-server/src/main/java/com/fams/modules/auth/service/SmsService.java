package com.fams.modules.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SmsService — gửi OTP qua SMS.
 *
 * DEV MODE (mặc định khi app.sms.dev-mode=true):
 *   OTP được log ra console thay vì gửi SMS thật.
 *   Dùng để test local mà không cần Firebase/SMS provider.
 *
 * PRODUCTION:
 *   Tích hợp Firebase Admin SDK hoặc SMS provider (ESMS, Twilio...).
 *   Set app.sms.dev-mode=false trong application.yml production.
 */
@Slf4j
@Service
public class SmsService {

    private final boolean devMode;

    public SmsService(@Value("${app.sms.dev-mode:true}") boolean devMode) {
        this.devMode = devMode;
    }

    /**
     * Gửi mã OTP đến số điện thoại.
     *
     * @param phone   Số điện thoại E.164 (VD: +84912345678)
     * @param otpCode Mã OTP 6 số
     */
    public void sendOtp(String phone, String otpCode) {
        if (devMode) {
            // ── DEV MODE: log OTP thay vì gửi SMS ────────────────────────────
            // Tìm dòng này trong console để lấy OTP khi test
            log.warn("╔══════════════════════════════════════╗");
            log.warn("║  [DEV] SMS OTP for {}  ║", phone);
            log.warn("║  OTP CODE: {}                    ║", otpCode);
            log.warn("║  Expires in 5 minutes                ║");
            log.warn("╚══════════════════════════════════════╝");
            return;
        }

        // ── PRODUCTION: tích hợp SMS provider ────────────────────────────────
        // TODO: Thay bằng Firebase Admin SDK hoặc ESMS/Twilio
        // Ví dụ với ESMS.vn:
        //   esmsClient.sendSms(phone, "Mã xác thực FAMS của bạn là: " + otpCode + ". Có hiệu lực trong 5 phút.");
        //
        // Ví dụ với Firebase Admin (dùng để gửi OTP về phone qua Firebase):
        //   firebaseAuth.createCustomToken(...) hoặc dùng Firebase Phone Auth REST API
        //
        // Hiện tại throw exception để nhắc nhở tích hợp
        throw new UnsupportedOperationException(
            "SMS provider chưa được cấu hình. Set app.sms.dev-mode=true để dùng dev mode.");
    }
}