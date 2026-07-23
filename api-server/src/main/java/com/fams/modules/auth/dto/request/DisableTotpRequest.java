package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Issue #5 (docs/issues/ISSUES.md): disabling 2FA requires proving you still control " +
        "the account — provide exactly one of password, code, or backupCode.")
public class DisableTotpRequest {

    @Schema(description = "Current account password", example = "MyP@ssw0rd")
    private String password;

    @Schema(description = "6-digit TOTP code from the Authenticator app", example = "123456")
    @Pattern(regexp = "^\\d{6}$", message = "Code must be exactly 6 digits")
    private String code;

    @Schema(description = "One of the backup codes issued when 2FA was enabled", example = "A1B2C3D4")
    private String backupCode;
}
