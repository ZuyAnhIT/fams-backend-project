package com.fams.modules.tenant.constant;

public final class TenantEventTypes {

    private TenantEventTypes() {
    }

    /** #133 (2026-08-19): sent to the tenant owner when a Platform Admin suspends their tenant —
     *  previously suspension was silent, the owner (and every user in that tenant, via the login
     *  block) only found out by being unable to log in or having every API call rejected. */
    public static final String TENANT_SUSPENDED_OWNER = "TENANT_SUSPENDED_OWNER";

    /** #133 (2026-08-19): sent to the tenant owner when a Platform Admin reactivates their tenant. */
    public static final String TENANT_REACTIVATED_OWNER = "TENANT_REACTIVATED_OWNER";
}
