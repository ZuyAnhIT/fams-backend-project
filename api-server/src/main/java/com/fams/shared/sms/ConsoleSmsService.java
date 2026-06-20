package com.fams.shared.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConsoleSmsService implements SmsService {

    @Override
    public void sendOtp(String phone, String otp) {
        log.info("[DEV SMS] OTP for {}: {}", phone, otp);
    }
}
