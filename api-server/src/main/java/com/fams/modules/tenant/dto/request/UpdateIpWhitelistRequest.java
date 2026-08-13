package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Partial update for an IP whitelist entry. All fields are optional.")
public class UpdateIpWhitelistRequest {

    @Schema(description = "New label for this entry (max 100 chars)", example = "Head office")
    @Size(max = 100, message = "Label must be at most 100 characters")
    private String label;

    @Schema(description = "Role names this entry restricts. Null = leave unchanged; empty list = "
            + "apply to every role; non-empty = only these roles are restricted by this entry "
            + "(other roles bypass it).", example = "[\"TENANT_ADMIN\", \"HR_MANAGER\"]")
    private List<String> applicableRoleNames;

    @Schema(description = "Set to false to disable this entry without deleting it", example = "true")
    private Boolean isActive;
}
