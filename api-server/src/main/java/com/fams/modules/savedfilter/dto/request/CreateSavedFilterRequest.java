package com.fams.modules.savedfilter.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Schema(description = "Request to save a new named filter for a list screen")
public class CreateSavedFilterRequest {

    @NotBlank(message = "resourceType is required")
    @Schema(description = "Which list screen this filter applies to", example = "violations")
    private String resourceType;

    @NotBlank(message = "name is required")
    @Schema(description = "Display name for this saved filter, unique per user+resourceType", example = "Vi phạm chưa xử lý tháng này")
    private String name;

    @NotNull(message = "filterParams is required")
    @Schema(description = "The exact query params to re-apply — stored and returned verbatim, "
            + "not interpreted by the backend", example = "{\"resolved\":false,\"violationType\":\"face_fail\"}")
    private Map<String, Object> filterParams;

    // Field deliberately NOT named isDefault: Lombok's accessor generation for a boolean field
    // already prefixed with "is" produces getter isDefault()/setter setDefault() — two DIFFERENT
    // property names by Jackson's own convention (Jackson strips "is" from getters but not from
    // "set..."), so a request body's "isDefault" would silently fail to bind. @JsonProperty pins
    // the wire name explicitly regardless of what Lombok names the accessors.
    @JsonProperty("isDefault")
    @Schema(description = "Set as the default filter for this resourceType — auto-applied when "
            + "the list screen loads. Setting true clears the previous default, if any.", example = "false")
    private boolean defaultFilter;
}
