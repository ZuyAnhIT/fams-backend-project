package com.fams.modules.tenant.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class TenantSettingsResponse {
    private UUID id;
    private UUID tenantId;
    private String dateFormat;
    private String timeFormat;
    private String brandPrimaryColor;
    private String brandSecondaryColor;
    private String brandAccentColor;
    private OffsetDateTime updatedAt;
}
