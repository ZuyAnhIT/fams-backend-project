package com.fams.modules.tenant.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTenantSettingsRequest {

    @Size(max = 30, message = "Date format must be at most 30 characters")
    private String dateFormat;

    @Size(max = 20, message = "Time format must be at most 20 characters")
    private String timeFormat;

    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand primary color must be a valid hex color (e.g. #RRGGBB)")
    private String brandPrimaryColor;

    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand secondary color must be a valid hex color (e.g. #RRGGBB)")
    private String brandSecondaryColor;

    @Pattern(regexp = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$",
             message = "Brand accent color must be a valid hex color (e.g. #RRGGBB)")
    private String brandAccentColor;
}
