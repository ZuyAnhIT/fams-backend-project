package com.fams.modules.randomcheck.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ManualCheckRequest {

    @NotNull(message = "siteId is required")
    private UUID siteId;

    @NotNull(message = "employeeId is required")
    private UUID employeeId;

    /**
     * Optional override for check mode. If omitted, uses the site/tenant config's checkMode.
     * Valid values: location_only, location_face, location_face_liveness.
     */
    private String checkMode;
}
