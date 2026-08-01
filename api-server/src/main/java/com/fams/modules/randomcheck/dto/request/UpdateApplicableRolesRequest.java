package com.fams.modules.randomcheck.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UpdateApplicableRolesRequest {

    @Schema(
        description = "Role names at the site that are subject to random checks — matched (string equality) "
                      + "against Assignment.role. Pass an empty list to apply checks to all roles. Assignment.role "
                      + "is currently constrained (both by @Pattern on create/update and a DB CHECK constraint) "
                      + "to exactly 'worker' or 'supervisor' — any other value here can never match a real "
                      + "assignment and is effectively dead configuration. Found via FE audit (2026-08-01): this "
                      + "field's storage is free-text (no matching constraint here), which previously led this "
                      + "doc to incorrectly imply arbitrary role names were meaningful.",
        example = "[\"worker\", \"supervisor\"]"
    )
    @NotNull(message = "applicableRoles must not be null — pass an empty list to target all roles")
    private List<@NotBlank(message = "Role names must not be blank") String> applicableRoles;
}
