package com.fams.modules.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// @JsonProperty("isPrimary") on the field below is not enough by itself: Jackson also
// auto-detects Lombok's isPrimary() getter as a SEPARATE property named "primary" (its own
// "is"-stripping convention), so both "isPrimary" and "primary" would appear in the JSON unless
// the auto-detected duplicate is explicitly ignored here.
@JsonIgnoreProperties({"primary"})
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

    // Explicit @JsonProperty: Lombok's isPrimary() getter on a primitive boolean field named
    // "isPrimary" makes Jackson strip the "is" prefix and serialize this as "primary" by default
    // (the same gotcha documented for other isXxx() booleans in this codebase) — forced back to
    // "isPrimary" so it matches the request DTO field name the frontend already binds to.
    @JsonProperty("isPrimary")
    @Schema(description = "True if this is the employee's primary workspace — at most one active "
            + "primary membership per employee")
    private boolean isPrimary;

    @Schema(description = "Date this membership starts (may be back- or future-dated by HR)")
    private LocalDate effectiveFrom;

    @Schema(description = "When this membership ended (transfer or removal), null while active")
    private OffsetDateTime leftAt;

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
