package com.fams.modules.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class IpWhitelistResponse {
    private UUID id;
    private UUID tenantId;
    private String ipAddress;
    private String label;
    private String scope;
    @JsonProperty("isActive")
    private boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
