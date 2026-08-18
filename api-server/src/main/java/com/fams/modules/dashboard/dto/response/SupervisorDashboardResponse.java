package com.fams.modules.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Site Supervisor dashboard — per-site employee presence for supervised sites")
public class SupervisorDashboardResponse {

    @Schema(description = "Status breakdown for each site this supervisor is actively assigned to")
    private List<SiteStatus> supervisedSites;

    @Data
    @Builder
    @Schema(description = "Attendance and presence status for a single supervised site")
    public static class SiteStatus {

        @Schema(description = "Site UUID")
        private UUID siteId;

        @Schema(description = "Site name")
        private String siteName;

        @Schema(description = "Total active assignments at this site today (expected workforce)")
        private int expectedToday;

        @Schema(description = "Number of employees currently on-site (open check-in session)")
        private int onSiteNow;

        @Schema(description = "Employees currently on-site with their check-in details")
        private List<OnSiteEmployee> onSiteEmployees;

        @Schema(description = "Site center latitude, for centering a map view (#130, docs/api/backend-feature-audit-2026-08-07.md)")
        private Double siteLatitude;

        @Schema(description = "Site center longitude")
        private Double siteLongitude;

        @Schema(description = "Random checks at this site still awaiting an employee response "
                + "(status pending or sent) — #121 (2026-08-18), previously the supervisor "
                + "dashboard only ever surfaced check-in presence")
        private long randomCheckPending;

        @Schema(description = "Unresolved violations at this site (#121, 2026-08-18)")
        private long unresolvedViolations;
    }

    @Data
    @Builder
    @Schema(description = "An employee currently present on-site")
    public static class OnSiteEmployee {

        @Schema(description = "Employee UUID")
        private UUID employeeId;

        @Schema(description = "Employee first name")
        private String firstName;

        @Schema(description = "Employee last name")
        private String lastName;

        @Schema(description = "Employee code")
        private String employeeCode;

        @Schema(description = "Check-in record UUID")
        private UUID checkinId;

        @Schema(description = "When the employee checked in")
        private OffsetDateTime checkInAt;

        @Schema(description = "Check-in GPS latitude — where this employee last checked in " +
                "(#130, docs/api/backend-feature-audit-2026-08-07.md: this is the check-in point, " +
                "not a continuously-tracked live position — FAMS has no background location tracking)")
        private Double checkInLat;

        @Schema(description = "Check-in GPS longitude")
        private Double checkInLon;
    }
}
