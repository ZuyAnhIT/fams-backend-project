package com.fams.modules.assignment.controller;

import com.fams.modules.assignment.dto.response.AssignmentResponse;
import com.fams.modules.assignment.service.AssignmentService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Cross-site "my assignments" self-service view — separate from {@link AssignmentController},
 *  which is always scoped to a single site via its URL path. No assignments:* permission is
 *  required: any authenticated user with an employee profile in this tenant may see their own
 *  assignment history, same trust model as AttendanceSummaryController's /attendance/me/monthly. */
@Slf4j
@Tag(name = "Assignments", description = "Employee-to-site assignment management")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/assignments")
public class AssignmentSelfController {

    private final AssignmentService assignmentService;

    public AssignmentSelfController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(
        summary = "Get my assignments (Employee)",
        description = "Returns every assignment (any site, any status) for the authenticated employee, " +
                      "most recent start date first. Not paginated. Requires only a valid JWT and an " +
                      "employee profile in this tenant — no assignments:* permission needed, since this is " +
                      "self-service access to the caller's own data."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "List of the caller's assignments",
            content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant not found, or no employee profile for this user in this tenant")
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<AssignmentResponse>>> getMyAssignments(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        List<AssignmentResponse> response = assignmentService.getMyAssignments(tenantId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
