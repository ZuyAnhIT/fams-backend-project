package com.fams.modules.rbac.constant;

public final class RoleEventTypes {

  private RoleEventTypes() {}

  /** Sent to the target user the moment a role is granted to them in a tenant. */
  public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";

  /** Sent to the target user the moment a role held by them is revoked — added 2026-08-15 so a
   *  person finds out they lost access immediately instead of discovering it the next time a
   *  request 403s. See docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md mục 10. */
  public static final String ROLE_REVOKED = "ROLE_REVOKED";
}
