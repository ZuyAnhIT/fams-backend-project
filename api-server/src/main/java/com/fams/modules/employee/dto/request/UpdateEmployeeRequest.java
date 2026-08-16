package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class UpdateEmployeeRequest {

    @Schema(description = "First name", example = "John")
    @Size(max = 100)
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    @Size(max = 100)
    private String lastName;

    @Schema(description = "Work email", example = "john.doe@example.com")
    @Email(message = "Must be a valid email address")
    private String email;

    @Schema(description = "Phone number", example = "+84901234567")
    @Size(max = 30)
    private String phone;

    @Schema(description = "Internal employee code — must be unique within the tenant", example = "EMP-001")
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z0-9\\-_]*$", message = "Employee code may only contain letters, digits, hyphens, and underscores")
    private String employeeCode;

    @Schema(description = "Job position / title", example = "Senior Site Engineer")
    @Size(max = 100)
    private String position;

    @Schema(description = "Department or team", example = "Construction")
    @Size(max = 100)
    private String department;

    @Schema(description = "National ID / CCCD / CMND", example = "001234567890")
    @Size(max = 50)
    private String nationalId;

    @Schema(description = "Date of hire", example = "2024-01-15")
    private LocalDate hiredDate;

    @Schema(description = "Avatar image URL")
    private String avatarUrl;

    @Schema(description = "Workspace UUID of type=department (optional — links employee to an org-chart workspace and syncs the department name field). See /tenants/{tenantId}/workspaces.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID departmentId;

    @Schema(description = "Intended role UUID for this person once they're invited/linked to a login account "
            + "(optional, only meaningful while the profile has no account yet).")
    private UUID plannedRoleId;
}
