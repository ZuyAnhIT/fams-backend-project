package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Complete a TOTP-gated login using the pending token and a 6-digit authenticator code")
public class LoginTotpRequest {

    @Schema(description = "Temporary pending token received in the initial login response when totpRequired=true", example = "eyJhbGciOiJIUzI1NiJ9...")
    @NotBlank(message = "Pending token is required")
    private String pendingToken;

    @Schema(description = "6-digit TOTP code from the Authenticator app", example = "123456")
    @NotBlank(message = "Code is required")
    @Pattern(regexp = "^\\d{6}$", message = "Code must be exactly 6 digits")
    private String code;
}
