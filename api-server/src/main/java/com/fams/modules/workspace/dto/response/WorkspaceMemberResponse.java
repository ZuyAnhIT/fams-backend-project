package com.fams.modules.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class WorkspaceMemberResponse {

    @Schema(description = "Membership record UUID")
    private UUID id;

    @Schema(description = "Workspace UUID")
    private UUID workspaceId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Workspace-level role", example = "member")
    private String role;

    @Schema(description = "Employee details")
    private EmployeeSummary employee;

    @Schema(description = "UUID of the user who made the assignment")
    private UUID assignedBy;

    @Schema(description = "Assignment timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    @Schema(description = "Brief employee details embedded in workspace member response")
    public static class EmployeeSummary {

        @Schema(description = "Employee UUID")
        private UUID id;

        @Schema(description = "Internal employee code", example = "EMP-001")
        private String employeeCode;

        @Schema(description = "First name", example = "John")
        private String firstName;

        @Schema(description = "Last name", example = "Doe")
        private String lastName;

        @Schema(description = "Full name", example = "John Doe")
        private String fullName;

        @Schema(description = "Job position / title", example = "Site Engineer")
        private String position;

        @Schema(description = "Work email", example = "john.doe@example.com")
        private String email;

        @Schema(description = "Employment status", example = "active",
                allowableValues = {"active", "inactive", "terminated"})
        private String status;
    }
}
