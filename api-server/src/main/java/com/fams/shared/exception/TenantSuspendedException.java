package com.fams.shared.exception;

public class TenantSuspendedException extends RuntimeException {
    public TenantSuspendedException() {
        super("Tenant account is suspended");
    }
}
