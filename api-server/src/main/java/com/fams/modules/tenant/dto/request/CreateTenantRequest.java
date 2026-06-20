package com.fams.modules.tenant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTenantRequest {

    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @NotBlank(message = "Slug is required")
    @Size(min = 2, max = 100, message = "Slug must be between 2 and 100 characters")
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
             message = "Slug must contain only lowercase letters, digits, and hyphens")
    private String slug;

    @Size(max = 255, message = "Domain must be at most 255 characters")
    private String domain;

    @Size(max = 100, message = "Industry must be at most 100 characters")
    private String industry;

    @Size(min = 2, max = 2, message = "Country code must be exactly 2 characters")
    private String countryCode;

    @Size(max = 100, message = "Timezone must be at most 100 characters")
    private String timezone;

    @Size(max = 10, message = "Locale must be at most 10 characters")
    private String locale;

    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters")
    private String currencyCode;
}
