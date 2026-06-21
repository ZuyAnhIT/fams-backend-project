package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Partial update for a tenant profile. All fields are optional.")
public class UpdateTenantRequest {

    @Schema(description = "New company display name (2–255 chars)", example = "Acme Corporation Ltd.")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @Schema(description = "New custom domain (max 255 chars)", example = "new.acme.com")
    @Size(max = 255, message = "Domain must be at most 255 characters")
    private String domain;

    @Schema(description = "URL of the company logo (max 2048 chars)", example = "https://cdn.example.com/logos/acme.png")
    @Size(max = 2048, message = "Logo URL must be at most 2048 characters")
    private String logoUrl;

    @Schema(description = "Industry sector (max 100 chars)", example = "Construction")
    @Size(max = 100, message = "Industry must be at most 100 characters")
    private String industry;

    @Schema(description = "ISO-3166-1 alpha-2 country code", example = "VN")
    @Size(min = 2, max = 2, message = "Country code must be exactly 2 characters")
    private String countryCode;

    @Schema(description = "IANA timezone identifier (max 100 chars)", example = "Asia/Ho_Chi_Minh")
    @Size(max = 100, message = "Timezone must be at most 100 characters")
    private String timezone;

    @Schema(description = "BCP-47 locale code (max 10 chars)", example = "vi-VN")
    @Size(max = 10, message = "Locale must be at most 10 characters")
    private String locale;

    @Schema(description = "ISO-4217 currency code", example = "VND")
    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters")
    private String currencyCode;
}
