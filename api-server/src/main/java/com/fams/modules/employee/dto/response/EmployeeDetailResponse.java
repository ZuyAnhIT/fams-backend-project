package com.fams.modules.employee.dto.response;

import com.fams.modules.rbac.dto.response.UserRoleResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Full employee profile including roles, workspaces, assignments, and Face ID status")
public class EmployeeDetailResponse {

    @Schema(description = "Employee UUID")
    private UUID id;

    @Schema(description = "Tenant this employee belongs to")
    private UUID tenantId;

    @Schema(description = "Linked auth user UUID (null until account is created)")
    private UUID userId;

    @Schema(description = "Internal employee code", example = "EMP-001")
    private String employeeCode;

    @Schema(description = "First name", example = "John")
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    private String lastName;

    @Schema(description = "Work email", example = "john.doe@example.com")
    private String email;

    @Schema(description = "Phone number", example = "+84901234567")
    private String phone;

    @Schema(description = "Job position / title", example = "Site Engineer")
    private String position;

    @Schema(description = "Department or team", example = "Construction")
    private String department;

    @Schema(description = "Managed department UUID (null if not linked to a predefined department)")
    private UUID departmentId;

    @Schema(description = "Employment status: active, inactive, terminated")
    private String status;

    @Schema(description = "Date of hire", example = "2024-01-15")
    private LocalDate hiredDate;

    @Schema(description = "Avatar image URL")
    private String avatarUrl;

    @Schema(description = "Active roles held by this employee within the tenant")
    private List<UserRoleResponse> roles;

    @Schema(description = "Workspace memberships — populated when workspace module is implemented (tasks 43-47)")
    private List<Object> workspaces;

    @Schema(description = "Site/shift assignments — populated when assignment module is implemented (tasks 63-66)")
    private List<Object> assignments;

    @Schema(description = "Face ID enrollment status for this employee")
    private FaceIdStatusDto faceId;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
