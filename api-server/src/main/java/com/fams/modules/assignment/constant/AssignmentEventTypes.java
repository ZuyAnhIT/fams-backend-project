package com.fams.modules.assignment.constant;

/**
 * Notification event types for site/shift assignments (#18/#19, 2026-09-03).
 *
 * <p>Before this, creating or cancelling an assignment produced <em>zero</em> notifications — a
 * worker only discovered a new posting by opening the App and pulling the check-in screen. These
 * two types feed the same {@code NotificationService.createNotification} choke point every other
 * event uses, so they get in-app inbox entries <em>and</em> FCM push (default opt-in) that the OS
 * can display while the App is closed, deep-linking to the check-in screen on tap.
 */
public final class AssignmentEventTypes {

    private AssignmentEventTypes() {
    }

    /** Sent to the assigned employee when a new site/shift assignment is created for them. */
    public static final String ASSIGNMENT_CREATED_EMPLOYEE = "ASSIGNMENT_CREATED_EMPLOYEE";

    /** Sent to the assigned employee when one of their assignments is cancelled. */
    public static final String ASSIGNMENT_CANCELLED_EMPLOYEE = "ASSIGNMENT_CANCELLED_EMPLOYEE";
}
