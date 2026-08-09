package com.fams.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Typed business rule violation — carries a machine-readable error code,
 * a human-readable Vietnamese user_message, and the intended HTTP status.
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String userMessage;
    private final HttpStatus httpStatus;

    public BusinessException(String errorCode, String userMessage, HttpStatus httpStatus, String technicalMessage) {
        super(technicalMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
