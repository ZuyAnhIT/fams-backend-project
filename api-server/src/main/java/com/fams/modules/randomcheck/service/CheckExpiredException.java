package com.fams.modules.randomcheck.service;

public class CheckExpiredException extends RuntimeException {
    public CheckExpiredException(String message) {
        super(message);
    }
}
