package com.fams.modules.search.dto.response;

import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.employee.dto.response.EmployeeResponse;
import com.fams.modules.site.dto.response.SiteResponse;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "Global quick-search results across employees, sites, check-ins, and violations")
public class GlobalSearchResponse {

    @Schema(description = "The search query that was executed", example = "nguyen")
    private String query;

    @Schema(description = "Max results returned per category", example = "5")
    private int limit;

    @Schema(description = "Matching employee records by human-recognizable fields such as full name, email, employee code, position, or department")
    private List<EmployeeResponse> employees;

    @Schema(description = "Matching site records by name, site code, address, or description")
    private List<SiteResponse> sites;

    @Schema(description = "Recent check-ins related to a matched employee/site, or latest records for the 'chấm công' category keyword")
    private List<CheckinResponse> checkins;

    @Schema(description = "Unresolved violations related to a matched employee/site, or latest records for the 'vi phạm' category keyword")
    private List<ViolationListResponse> violations;
}
