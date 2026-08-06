package com.fams.modules.savedfilter.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Schema(description = "Request to update an existing saved filter — all fields optional, only sent fields change")
public class UpdateSavedFilterRequest {

    @Schema(description = "New display name (optional)")
    private String name;

    @Schema(description = "New filter params, replaces the old set entirely (optional)")
    private Map<String, Object> filterParams;

    // See CreateSavedFilterRequest for why this isn't named isDefault directly.
    @JsonProperty("isDefault")
    @Schema(description = "Set/clear default status (optional)")
    private Boolean defaultFilter;
}
