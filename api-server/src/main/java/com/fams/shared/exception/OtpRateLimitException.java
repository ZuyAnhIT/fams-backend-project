package com.fams.shared.exception;

public class OtpRateLimitException extends RuntimeException {
    public OtpRateLimitException() {
        super("Too many OTP requests. Please wait before trying again.");
    }
}
