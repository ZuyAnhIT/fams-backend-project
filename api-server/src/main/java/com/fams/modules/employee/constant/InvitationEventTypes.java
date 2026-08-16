package com.fams.modules.employee.constant;

public final class InvitationEventTypes {

  private InvitationEventTypes() {}

  /** Sent to an existing FAMS user (by email match) the moment they're invited into a tenant
   *  they don't already belong to. Brand-new-email invitees have no user_id yet, so they can
   *  only be reached by email — this only fires for the "already has an account" case. */
  public static final String EMPLOYEE_INVITED = "EMPLOYEE_INVITED";

  /** Sent to the inviter (invitedBy) when the person they invited accepts. */
  public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";
}
