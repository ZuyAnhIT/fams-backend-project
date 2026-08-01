package com.fams.modules.randomcheck.dto.request;

import jakarta.validation.constraints.NotBlank;
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

    /**
     * Required — manual checks bypass the config's applicableRoles population filter by design
     * (targeting one specific employee is an explicit override of general sampling policy), so a
     * reason gives an audit trail for why this employee was singled out. See
     * docs/api/random-check-config-review.md §2.
     */
    @NotBlank(message = "reason is required")
    private String reason;
}
