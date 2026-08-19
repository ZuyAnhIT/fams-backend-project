package com.fams.modules.report.dto.response;

import com.fams.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
@Schema(description = "Real-time site presence report — how many employees are present vs absent at each site right now")
public class SitePresenceReportResponse {

    @Schema(description = "Timestamp when this snapshot was taken (UTC)")
    private OffsetDateTime reportedAt;

    @Schema(description = "Total number of sites included in the report", example = "5")
    private int totalSites;

    @Schema(description = "Sum of presentCount across all sites", example = "42")
    private int totalPresent;

    @Schema(description = "Sum of assignedCount across all sites", example = "55")
    private int totalAssigned;

    @Schema(description = "Sum of absentCount across all sites", example = "13")
    private int totalAbsent;

    @Schema(description = "Sum of unresolvedViolations across all sites (#126, 2026-08-18)", example = "2")
    private long totalUnresolvedViolations;

    @Schema(description = "Paginated per-site presence breakdown")
    private PageResponse<SitePresenceEntry> sites;
}
