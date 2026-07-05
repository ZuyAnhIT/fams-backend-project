package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of validating an invitation token before the accept flow")
public class ValidateInvitationResponse {

    @Schema(description = "Email address the invitation was sent to", example = "employee@example.com")
    private String email;

    @Schema(description = "Whether this email already has an account; if true, frontend should show login form instead of password creation")
    @com.fasterxml.jackson.annotation.JsonProperty("isExistingUser")
    private boolean isExistingUser;

    @Schema(description = "Display name of the tenant the user is being invited to", example = "Công ty ABC")
    private String tenantName;
}
