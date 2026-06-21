package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Partial update for an IP whitelist entry. All fields are optional.")
public class UpdateIpWhitelistRequest {

    @Schema(description = "New label for this entry (max 100 chars)", example = "Head office")
    @Size(max = 100, message = "Label must be at most 100 characters")
    private String label;

    @Schema(description = "New scope: web_admin, api, or all", example = "web_admin")
    @Pattern(regexp = "^(web_admin|api|all)$", message = "Scope must be one of: web_admin, api, all")
    private String scope;

    @Schema(description = "Set to false to disable this entry without deleting it", example = "true")
    private Boolean isActive;
}
