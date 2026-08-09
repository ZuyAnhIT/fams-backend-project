package com.fams.shared.exception;

public class InvalidOtpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidOtpException() {
        super("Invalid or expired OTP");
    }
}
