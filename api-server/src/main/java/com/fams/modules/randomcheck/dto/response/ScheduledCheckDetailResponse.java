package com.fams.modules.randomcheck.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class ScheduledCheckDetailResponse {

    @Schema(description = "Scheduled check UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Assignment UUID")
    private UUID assignmentId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Shift UUID")
    private UUID shiftId;

    @Schema(description = "Config UUID used to generate this check")
    private UUID configId;

    @Schema(description = "Snapshot of the config fields at generation time (JSON)")
    private String configSnapshot;

    @Schema(description = "Date this check was generated for", example = "2026-06-28")
    private LocalDate checkDate;

    @Schema(description = "1-based index (manual checks use 0, -1, -2...)", example = "1")
    private int checkIndex;

    @Schema(description = "When the check notification was dispatched")
    private OffsetDateTime scheduledAt;

    @Schema(description = "Deadline for the employee to respond")
    private OffsetDateTime expiresAt;

    @Schema(description = "Status: pending | sent | responded | no_response | cancelled")
    private String status;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Employee response, present only when status is 'responded'")
    private CheckResponseDto response;

    @Schema(description = "Only set for manually (HR-)triggered checks — the required reason given for "
            + "targeting this specific employee, bypassing the config's applicableRoles filter")
    private String manualReason;

    @Schema(description = "Only set for manually (HR-)triggered checks — the user who triggered it")
    private UUID triggeredBy;

    @Schema(description = "'auto' or 'manual_hr' — derived from triggeredBy (#108/#109, 2026-08-18)")
    private String triggerType;

    @Schema(description = "#100 (2026-08-18): when the check was actually dispatched (status "
            + "flipped to 'sent') — null if never sent")
    private OffsetDateTime sentAt;

    @Schema(description = "#100 (2026-08-18): the Notification row created for this check's "
            + "dispatch, if any — null if never sent or notification creation failed")
    private UUID notificationId;

    @Schema(description = "#99 (2026-08-18): who cancelled this check — null for system-triggered "
            + "cancellation (e.g. assignment cancelled) or if never cancelled")
    private UUID cancelledBy;

    @Schema(description = "#99 (2026-08-18): when this check was cancelled — null if never cancelled")
    private OffsetDateTime cancelledAt;

    @Schema(description = "#99 (2026-08-18): why this check was cancelled — null if never "
            + "cancelled or no reason was given")
    private String cancelledReason;

    @Schema(description = "Violation(s) raised from this check, if any (no_response, or "
            + "location_fail/face_fail/liveness_fail off the response) — embedded so HR can see "
            + "the full story of one check (dispute resolution) without a separate GET /violations "
            + "call and client-side matching. Usually 0 or 1 entries; can be 2 for a single "
            + "response that failed BOTH location and face/liveness checks.")
    private List<ViolationSummary> violations;

    @Getter
    @Builder
    public static class ViolationSummary {
        private UUID id;
        private String violationType;
        private boolean resolved;
        private String resolution;
        private String description;
    }
}
