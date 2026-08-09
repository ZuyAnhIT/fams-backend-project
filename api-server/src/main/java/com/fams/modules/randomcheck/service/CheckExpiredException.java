package com.fams.modules.randomcheck.service;

public class CheckExpiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CheckExpiredException(String message) {
        super(message);
    }
}
