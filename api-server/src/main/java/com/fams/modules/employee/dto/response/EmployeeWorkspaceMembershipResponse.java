package com.fams.modules.employee.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

// See WorkspaceMemberResponse for why both @JsonProperty("isPrimary") on the field AND this
// class-level @JsonIgnoreProperties are needed to avoid Jackson emitting a duplicate "primary" key.
@JsonIgnoreProperties({"primary"})
@Data
@Builder
@Schema(description = "One workspace this employee belongs to, as shown on the employee detail screen")
public class EmployeeWorkspaceMembershipResponse {

    @Schema(description = "Workspace membership UUID")
    private UUID id;

    @Schema(description = "Workspace UUID")
    private UUID workspaceId;

    @Schema(description = "Workspace display name")
    private String workspaceName;

    @Schema(description = "Employee's role within the workspace", example = "member")
    private String role;

    @JsonProperty("isPrimary")
    @Schema(description = "True if this is the employee's primary workspace")
    private boolean isPrimary;

    @Schema(description = "When this employee joined the workspace")
    private OffsetDateTime assignedAt;
}
