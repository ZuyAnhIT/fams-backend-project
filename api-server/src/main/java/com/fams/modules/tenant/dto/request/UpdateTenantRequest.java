package com.fams.modules.tenant.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTenantRequest {

    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @Size(max = 255, message = "Domain must be at most 255 characters")
    private String domain;

    @Size(max = 2048, message = "Logo URL must be at most 2048 characters")
    private String logoUrl;

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
