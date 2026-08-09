package com.fams.shared.exception;

public class EmailNotVerifiedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EmailNotVerifiedException() {
        super("Email address has not been verified. Please check your inbox for the verification link.");
    }
}
