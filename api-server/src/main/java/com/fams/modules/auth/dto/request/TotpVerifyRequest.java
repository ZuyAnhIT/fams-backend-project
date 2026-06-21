package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Confirm TOTP setup by verifying the first code from the Authenticator app")
public class TotpVerifyRequest {

    @Schema(description = "Setup token received from the /totp/setup endpoint", example = "eyJhbGciOiJIUzI1NiJ9...")
    @NotBlank(message = "Setup token is required")
    private String setupToken;

    @Schema(description = "6-digit TOTP code from the Authenticator app", example = "123456")
    @NotBlank(message = "Code is required")
    @Pattern(regexp = "^\\d{6}$", message = "Code must be exactly 6 digits")
    private String code;
}
