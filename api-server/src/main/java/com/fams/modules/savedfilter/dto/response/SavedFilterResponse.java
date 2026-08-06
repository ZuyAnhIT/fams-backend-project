package com.fams.modules.savedfilter.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A saved filter for a list screen")
public class SavedFilterResponse {

    @Schema(description = "Saved filter UUID")
    private UUID id;

    @Schema(description = "Which list screen this filter applies to", example = "violations")
    private String resourceType;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "The exact query params to re-apply to the list endpoint")
    private Map<String, Object> filterParams;

    // See CreateSavedFilterRequest for why this isn't named isDefault directly.
    @JsonProperty("isDefault")
    @Schema(description = "Whether this is the default filter for this resourceType — auto-apply on screen load")
    private boolean defaultFilter;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
