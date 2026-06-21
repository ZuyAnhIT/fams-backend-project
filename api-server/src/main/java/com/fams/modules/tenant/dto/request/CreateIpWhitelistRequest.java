package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Add an IP address or CIDR range to a tenant's whitelist")
public class CreateIpWhitelistRequest {

    @Schema(description = "IPv4, IPv6, or CIDR notation (max 50 chars)", example = "192.168.1.0/24")
    @NotBlank(message = "IP address is required")
    @Size(max = 50, message = "IP address must be at most 50 characters")
    @Pattern(
        regexp = "^(([0-9]{1,3}\\.){3}[0-9]{1,3}(/([0-9]|[1-2][0-9]|3[0-2]))?|([0-9a-fA-F:]+)(/[0-9]{1,3})?)$",
        message = "Must be a valid IPv4, IPv6, or CIDR notation (e.g. 192.168.1.0/24)"
    )
    private String ipAddress;

    @Schema(description = "Human-readable label for this entry (max 100 chars)", example = "Office network")
    @Size(max = 100, message = "Label must be at most 100 characters")
    private String label;

    @Schema(description = "Scope this entry applies to: web_admin, api, or all", example = "all")
    @Pattern(regexp = "^(web_admin|api|all)$", message = "Scope must be one of: web_admin, api, all")
    private String scope;
}
