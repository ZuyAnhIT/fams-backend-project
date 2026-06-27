package com.fams.modules.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateWorkspaceRequest {

    @Schema(description = "Workspace name — must be unique within the tenant", example = "Engineering")
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "Optional description", example = "Handles all technical development work")
    private String description;

    @Schema(description = "Workspace type: 'department' or 'team'", example = "department",
            allowableValues = {"department", "team"})
    @Pattern(regexp = "^(department|team)$", message = "Type must be 'department' or 'team'")
    private String type;

    @Schema(description = "Parent workspace UUID for hierarchical structure (optional)")
    private UUID parentId;
}
