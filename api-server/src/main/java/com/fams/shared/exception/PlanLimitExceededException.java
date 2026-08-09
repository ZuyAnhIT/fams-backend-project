package com.fams.shared.exception;

public class PlanLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlanLimitExceededException(String message) {
        super(message);
    }
}
