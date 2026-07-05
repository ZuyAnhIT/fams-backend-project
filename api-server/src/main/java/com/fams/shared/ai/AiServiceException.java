package com.fams.shared.ai;

import lombok.Getter;

@Getter
public class AiServiceException extends RuntimeException {

    private final int statusCode;

    public AiServiceException(String message) {
        super(message);
        this.statusCode = 500;
    }

    public AiServiceException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
