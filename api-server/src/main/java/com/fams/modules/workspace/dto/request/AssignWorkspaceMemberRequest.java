package com.fams.modules.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class AssignWorkspaceMemberRequest {

    @Schema(description = "Employee UUID to assign to this workspace", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @NotNull(message = "employeeId is required")
    private UUID employeeId;

    @Schema(description = "Workspace-level role: member | lead | manager (default: member)",
            example = "member", allowableValues = {"member", "lead", "manager"})
    @Pattern(regexp = "^(member|lead|manager)$", message = "Role must be 'member', 'lead', or 'manager'")
    private String role;

    @Schema(description = "Date this membership starts (optional, default: today). Can be back- or "
            + "future-dated.", example = "2026-09-01")
    private LocalDate effectiveFrom;

    @Schema(description = "Mark this as the employee's primary workspace (optional). If true, any "
            + "existing primary membership for this employee is automatically demoted. If omitted, "
            + "defaults to true only when the employee has no other active primary workspace yet.")
    private Boolean isPrimary;
}
