package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Result of enabling TOTP 2FA — includes one-time backup codes (Issue #5, docs/issues/ISSUES.md)")
public class TotpEnableResponse {

    @Schema(description = "One-time recovery codes, shown ONLY now — save them somewhere safe. " +
            "Each can be used once in place of a TOTP code if the authenticator device is lost.",
            example = "[\"A1B2C3D4\", \"E5F6G7H8\"]")
    private List<String> backupCodes;
}
