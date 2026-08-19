package com.fams.modules.report.dto.response;

import com.fams.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Face ID enrollment status report — aggregate counts plus per-employee rows")
public class FaceIdReportResponse {

    @Schema(description = "Total active employees in the tenant", example = "120")
    private int totalEmployees;

    @Schema(description = "Number of employees with status = enrolled", example = "95")
    private int enrolledCount;

    @Schema(description = "Number of employees with status = pending (consent given, enrollment in progress)", example = "5")
    private int pendingCount;

    @Schema(description = "Number of employees who have never created a Face ID record or status = not_enrolled", example = "18")
    private int notEnrolledCount;

    @Schema(description = "Number of employees with status = revoked", example = "2")
    private int revokedCount;

    @Schema(description = "Status filter applied (null = all statuses)", example = "not_enrolled")
    private String statusFilter;

    @Schema(description = "Department filter applied (null = all departments) — added 2026-08-05")
    private java.util.UUID departmentId;

    @Schema(description = "Site filter applied (null = all sites) — added 2026-08-18 (#127)")
    private java.util.UUID siteId;

    @Schema(description = "Server-side name/email/code search term applied (null = none) — added 2026-08-05")
    private String search;

    @Schema(description = "Paginated employee Face ID rows matching the filters")
    private PageResponse<FaceIdReportRow> records;
}
