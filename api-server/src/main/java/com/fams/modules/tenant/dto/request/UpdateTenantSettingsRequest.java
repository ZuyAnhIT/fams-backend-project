package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Partial update for tenant UI and branding settings. All fields are optional.")
public class UpdateTenantSettingsRequest {

    @Schema(description = "Date format string (max 30 chars)", example = "DD/MM/YYYY")
    @Size(max = 30, message = "Date format must be at most 30 characters")
    private String dateFormat;

    @Schema(description = "Time format string (max 20 chars)", example = "HH:mm")
    @Size(max = 20, message = "Time format must be at most 20 characters")
    private String timeFormat;

    @Schema(description = "Brand primary color as hex (e.g. #RRGGBB)", example = "#1A73E8")
    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand primary color must be a valid hex color (e.g. #RRGGBB)")
    private String brandPrimaryColor;

    @Schema(description = "Brand secondary color as hex", example = "#FBBC05")
    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand secondary color must be a valid hex color (e.g. #RRGGBB)")
    private String brandSecondaryColor;

    @Schema(description = "Brand accent color as hex", example = "#34A853")
    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand accent color must be a valid hex color (e.g. #RRGGBB)")
    private String brandAccentColor;

    @Schema(description = "Prefix for auto-generated employee codes (max 20 chars, alphanumeric + hyphens). Set to empty string to disable auto-generation.", example = "EMP")
    @Size(max = 20)
    @Pattern(regexp = "^[A-Za-z0-9\\-]*$", message = "Prefix may only contain letters, digits, and hyphens")
    private String employeeCodePrefix;

    @Schema(description = "Number of zero-padded digits in the auto-generated sequence (1–8)", example = "4")
    @Min(1) @Max(8)
    private Integer employeeCodePadding;

    @Schema(description = "#144 (2026-08-19): override how long (in days) DataRetentionJob keeps "
            + "this tenant's notifications and biometric photos before purging. Null/omitted = use "
            + "the platform-wide default. Pass clearDataRetentionDays=true to explicitly revert to "
            + "the platform default.", example = "60")
    @Min(value = 7, message = "dataRetentionDays must be at least 7")
    @Max(value = 3650, message = "dataRetentionDays must be at most 3650 (10 years)")
    private Integer dataRetentionDays;

    @Schema(description = "When true, clears dataRetentionDays back to the platform-wide default "
            + "regardless of the dataRetentionDays field value")
    private boolean clearDataRetentionDays;
}
