package com.fams.shared.exception;

public class PhoneNotVerifiedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PhoneNotVerifiedException(String message) {
        super(message);
    }
}
