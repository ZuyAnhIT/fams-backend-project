package com.fams.modules.rbac.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of validating a platform-staff invitation token before the accept flow")
public class ValidatePlatformInvitationResponse {

    @Schema(description = "Email address the invitation was sent to", example = "newstaff@fams.com")
    private String email;

    @Schema(description = "Whether this email already has an account; if true, the frontend should not ask for a password")
    @JsonProperty("isExistingUser")
    private boolean existingUser;
}
