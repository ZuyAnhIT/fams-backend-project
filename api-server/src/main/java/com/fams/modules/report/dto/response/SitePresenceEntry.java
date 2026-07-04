package com.fams.modules.report.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Real-time presence snapshot for a single site")
public class SitePresenceEntry {

    @Schema(description = "Site ID")
    private UUID siteId;

    @Schema(description = "Site name", example = "HQ")
    private String siteName;

    @Schema(description = "Site timezone", example = "Asia/Ho_Chi_Minh")
    private String timezone;

    @Schema(description = "Employees with active assignments for today at this site", example = "10")
    private int assignedCount;

    @Schema(description = "Employees with an open check-in session right now", example = "7")
    private int presentCount;

    @Schema(description = "Assigned employees not yet checked in (assignedCount - presentCount)", example = "3")
    private int absentCount;

    @Schema(description = "Employee IDs currently checked in at this site")
    private List<UUID> presentEmployeeIds;

    @Schema(description = "Employee IDs assigned today but not yet checked in")
    private List<UUID> absentEmployeeIds;
}
