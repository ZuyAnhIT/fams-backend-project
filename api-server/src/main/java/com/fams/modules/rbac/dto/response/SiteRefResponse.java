package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Minimal site reference — id + display name, for showing a site-scoped role assignment's scope without a follow-up call to GET /sites")
public class SiteRefResponse {

    @Schema(description = "Site UUID")
    private UUID id;

    @Schema(description = "Site display name", example = "Site Quận 1")
    private String name;
}
