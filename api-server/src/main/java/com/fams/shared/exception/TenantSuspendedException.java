package com.fams.shared.exception;

public class TenantSuspendedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TenantSuspendedException() {
        super("Tenant account is suspended");
    }
}
