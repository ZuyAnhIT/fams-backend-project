package com.fams.shared.sms;

public interface SmsService {
    void sendOtp(String phone, String otp);
}
