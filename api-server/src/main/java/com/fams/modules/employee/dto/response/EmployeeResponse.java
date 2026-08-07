package com.fams.modules.employee.dto.response;

import com.fams.shared.util.Masked;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Employee profile summary")
public class EmployeeResponse {

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

    @Masked(type = Masked.MaskType.EMAIL)
    @Schema(description = "Work email", example = "john.doe@example.com")
    private String email;

    @Masked(type = Masked.MaskType.PHONE)
    @Schema(description = "Phone number", example = "+84901234567")
    private String phone;

    @Schema(description = "Job position / title", example = "Site Engineer")
    private String position;

    @Schema(description = "Department or team", example = "Construction")
    private String department;

    @Schema(description = "Workspace UUID of type=department (null if not linked to one). See /tenants/{tenantId}/workspaces.")
    private UUID departmentId;

    @Schema(description = "Employment status: active, inactive, terminated")
    private String status;

    @Schema(description = "Date of hire", example = "2024-01-15")
    private LocalDate hiredDate;

    @Schema(description = "Avatar image URL")
    private String avatarUrl;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;

    @Schema(description = "Face ID enrollment status for this employee")
    private FaceIdStatusDto faceId;

    @Schema(description = "True if email/phone in this response are masked for the calling user "
            + "(no employees:pii:read permission / not PLATFORM_ADMIN) — explicit flag added "
            + "2026-08-06 so clients don't have to heuristically detect a \"***\" pattern; the "
            + "masking decision itself always stays server-side regardless of what a client does "
            + "with this flag.")
    private boolean piiMasked;
}
