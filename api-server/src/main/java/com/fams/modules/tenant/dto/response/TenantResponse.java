package com.fams.modules.tenant.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class TenantResponse {
    private UUID id;
    private String name;
    private String slug;
    private String domain;
    private String logoUrl;
    private String industry;
    private String countryCode;
    private String timezone;
    private String locale;
    private String currencyCode;
    private String status;
    private UUID ownerId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
