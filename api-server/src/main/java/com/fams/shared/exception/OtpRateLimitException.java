package com.fams.shared.exception;

public class OtpRateLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OtpRateLimitException() {
        super("Too many OTP requests. Please wait before trying again.");
    }
}
