package com.fams.modules.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "An IP whitelist entry for a tenant")
public class IpWhitelistResponse {

    @Schema(description = "Entry UUID")
    private UUID id;

    @Schema(description = "UUID of the owning tenant")
    private UUID tenantId;

    @Schema(description = "IPv4, IPv6, or CIDR notation", example = "192.168.1.0/24")
    private String ipAddress;

    @Schema(description = "Human-readable label", example = "Office network")
    private String label;

    @Schema(description = "Role names this entry restricts. Empty = applies to every role.",
            example = "[\"TENANT_ADMIN\", \"HR_MANAGER\"]")
    private List<String> applicableRoleNames;

    @Schema(description = "Whether this entry is currently enforced")
    @JsonProperty("isActive")
    private boolean isActive;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
