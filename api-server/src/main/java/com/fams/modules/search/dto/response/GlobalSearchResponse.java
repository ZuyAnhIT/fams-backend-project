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

    @Schema(description = "Matching employee records (by name, email, or employee code)")
    private List<EmployeeResponse> employees;

    @Schema(description = "Matching site records (by name, code, or address)")
    private List<SiteResponse> sites;

    @Schema(description = "Recent check-ins for employees that matched the query")
    private List<CheckinResponse> checkins;

    @Schema(description = "Unresolved violations for employees that matched the query, plus a "
            + "direct exact-UUID lookup (#128, 2026-08-18) — mirrors the check-in group's shape")
    private List<ViolationListResponse> violations;
}
