package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "TOTP setup details needed to configure an Authenticator app")
public class TotpSetupResponse {

    @Schema(description = "Short-lived token used to confirm setup via /totp/verify", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String setupToken;

    @Schema(description = "URL to the QR code page that the Authenticator app can scan", example = "http://localhost:8080/api/v1/auth/totp/qr?token=eyJ...")
    private String qrCodeUrl;

    @Schema(description = "Base32 secret key for manual entry in the Authenticator app", example = "JBSWY3DPEHPK3PXP")
    private String manualEntryKey;
}
