package com.fams.shared.exception;

public class InvalidInvitationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidInvitationException(String message) {
        super(message);
    }
}
