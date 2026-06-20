package com.fams.modules.tenant.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateIpWhitelistRequest {

    @Size(max = 100, message = "Label must be at most 100 characters")
    private String label;

    @Pattern(regexp = "^(web_admin|api|all)$", message = "Scope must be one of: web_admin, api, all")
    private String scope;

    private Boolean isActive;
}
