package com.fams.shared.exception;

/**
 * Issue #8 (docs/issues/ISSUES.md): thrown when deactivating a plan is blocked because tenants
 * are still subscribed to it and either no migration target was given, or migrating them would
 * violate the target plan's limits.
 */
public class PlanDeactivationBlockedException extends RuntimeException {
    public PlanDeactivationBlockedException(String message) {
        super(message);
    }
}
