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
        description = "Role names at the site that are subject to random checks. " +
                      "Pass an empty list to apply checks to all roles. " +
                      "Role names are free-form strings matched against the employee's role_at_site field.",
        example = "[\"supervisor\", \"employee\"]"
    )
    @NotNull(message = "applicableRoles must not be null — pass an empty list to target all roles")
    private List<@NotBlank(message = "Role names must not be blank") String> applicableRoles;
}
