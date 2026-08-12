package com.fams.modules.report.dto.response;

import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Violation report for a date range — aggregate stats plus paginated individual records")
public class ViolationReportResponse {

    @Schema(description = "Start of report period (inclusive, null = no lower bound)", example = "2026-07-01")
    private LocalDate from;

    @Schema(description = "End of report period (inclusive, null = no upper bound)", example = "2026-07-31")
    private LocalDate to;

    @Schema(description = "Site filter applied (null = all sites)", example = "null")
    private UUID siteId;

    @Schema(description = "Employee filter applied (null = all employees)", example = "null")
    private UUID employeeId;

    @Schema(description = "Violation type filter applied (null = all types)", example = "null")
    private String violationType;

    @Schema(description = "Total violations in the period matching the filters", example = "27")
    private int totalViolations;

    @Schema(description = "Number of resolved violations", example = "20")
    private int resolvedCount;

    @Schema(description = "Number of unresolved violations", example = "7")
    private int unresolvedCount;

    @Schema(description = "Number of violations flagged as affecting attendance", example = "5")
    private int affectsAttendanceCount;

    @Schema(description = "Violation count grouped by violation type (no_response, location_fail, face_fail, liveness_fail, face_verify_timeout)")
    private Map<String, Long> byViolationType;

    @Schema(description = "Violation count grouped by severity (HIGH/MEDIUM/LOW), derived from violationType — "
            + "HIGH: face_fail/liveness_fail (identity verification failure); MEDIUM: location_fail; "
            + "LOW: no_response. Not a separately editable field, see ViolationSeverity")
    private Map<String, Long> bySeverity;

    @Schema(description = "Violation count grouped by site ID (string UUID → count)")
    private Map<String, Long> bySite;

    @Schema(description = "Violation count grouped by employee ID (string UUID → count)")
    private Map<String, Long> byEmployee;

    @Schema(description = "Paginated individual violation records matching the filters")
    private PageResponse<ViolationListResponse> records;
}
