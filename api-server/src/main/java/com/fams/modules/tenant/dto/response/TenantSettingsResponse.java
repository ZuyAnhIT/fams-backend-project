package com.fams.modules.tenant.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Tenant UI and branding settings")
public class TenantSettingsResponse {

    @Schema(description = "Settings record UUID")
    private UUID id;

    @Schema(description = "UUID of the owning tenant")
    private UUID tenantId;

    @Schema(description = "Date format string", example = "DD/MM/YYYY")
    private String dateFormat;

    @Schema(description = "Time format string", example = "HH:mm")
    private String timeFormat;

    @Schema(description = "Brand primary color as hex", example = "#1A73E8")
    private String brandPrimaryColor;

    @Schema(description = "Brand secondary color as hex", example = "#FBBC05")
    private String brandSecondaryColor;

    @Schema(description = "Brand accent color as hex", example = "#34A853")
    private String brandAccentColor;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
