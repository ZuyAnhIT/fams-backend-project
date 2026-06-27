package com.fams.modules.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

@Data
public class TransferWorkspaceMemberRequest {

    @Schema(description = "UUID of the target workspace to transfer the employee into",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @NotNull(message = "targetWorkspaceId is required")
    private UUID targetWorkspaceId;

    @Schema(description = "Workspace-level role in the target workspace (inherits current role if omitted)",
            example = "member", allowableValues = {"member", "lead", "manager"})
    @Pattern(regexp = "^(member|lead|manager)$", message = "Role must be 'member', 'lead', or 'manager'")
    private String role;
}
