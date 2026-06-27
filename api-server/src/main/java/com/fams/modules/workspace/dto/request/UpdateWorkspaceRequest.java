package com.fams.modules.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateWorkspaceRequest {

    @Schema(description = "New workspace name — must be unique within the tenant", example = "Engineering Dept")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "New description")
    private String description;

    @Schema(description = "Workspace type: 'department' or 'team'", example = "department",
            allowableValues = {"department", "team"})
    @Pattern(regexp = "^(department|team)$", message = "Type must be 'department' or 'team'")
    private String type;

    @Schema(description = "New parent workspace UUID. Pass null to make this a root workspace.")
    private UUID parentId;

    @Schema(description = "Whether to explicitly clear the parent (make root). " +
                          "Set to true when parentId is intentionally null.", example = "false")
    private boolean clearParent;

    @Schema(description = "Status: 'active' or 'inactive'", example = "active",
            allowableValues = {"active", "inactive"})
    @Pattern(regexp = "^(active|inactive)$", message = "Status must be 'active' or 'inactive'")
    private String status;
}
