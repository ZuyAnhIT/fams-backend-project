package com.fams.modules.tenant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateIpWhitelistRequest {

    @NotBlank(message = "IP address is required")
    @Size(max = 50, message = "IP address must be at most 50 characters")
    @Pattern(
        regexp = "^(([0-9]{1,3}\\.){3}[0-9]{1,3}(/([0-9]|[1-2][0-9]|3[0-2]))?|([0-9a-fA-F:]+)(/[0-9]{1,3})?)$",
        message = "Must be a valid IPv4, IPv6, or CIDR notation (e.g. 192.168.1.0/24)"
    )
    private String ipAddress;

    @Size(max = 100, message = "Label must be at most 100 characters")
    private String label;

    @Pattern(regexp = "^(web_admin|api|all)$", message = "Scope must be one of: web_admin, api, all")
    private String scope;
}
