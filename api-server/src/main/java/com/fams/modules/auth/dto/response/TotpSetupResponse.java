package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "TOTP setup details needed to configure an Authenticator app")
public class TotpSetupResponse {

    @Schema(description = "Short-lived token used to confirm setup via /totp/verify", example = "550e8400-e29b-41d4-a716-446655440000")
    private String setupToken;

    // Contract change 2026-08-12 (FE request): X-Frame-Options: DENY (kept, not weakened —
    // see AuthController) blocks embedding qrCodeUrl's HTML page in an iframe, which is how FE
    // was rendering the QR before this field existed. otpauthUri lets FE/App render the QR
    // client-side (e.g. a JS QR lib) instead of needing an iframe at all.
    @Schema(description = "otpauth:// provisioning URI — render this as a QR code client-side "
            + "(e.g. with a JS/mobile QR library). issuer and account are URL-encoded; secret is "
            + "the same value as manualEntryKey.",
            example = "otpauth://totp/FAMS:user%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=FAMS&algorithm=SHA1&digits=6&period=30")
    private String otpauthUri;

    @Schema(description = "Base32 secret key for manual entry in the Authenticator app — identical to the "
            + "secret embedded in otpauthUri", example = "JBSWY3DPEHPK3PXP")
    private String manualEntryKey;

    // Deprecated 2026-08-12: kept only for old clients that haven't switched to rendering
    // otpauthUri themselves, and as a fallback that can be opened directly in a browser tab for
    // manual verification. New clients should use otpauthUri and MUST NOT embed this URL in an
    // iframe — X-Frame-Options: DENY on this endpoint is intentional and will not be relaxed.
    @Deprecated
    @Schema(description = "DEPRECATED — use otpauthUri instead. URL to an HTML page that renders the QR "
            + "(server-side, via a CDN-loaded JS lib). Cannot be embedded in an iframe (X-Frame-Options: DENY, "
            + "not weakened for this). Retained for backward compatibility with old clients and for opening "
            + "directly in a browser tab.",
            example = "http://localhost:8080/api/v1/auth/totp/qr?token=550e8400-e29b-41d4-a716-446655440000",
            deprecated = true)
    private String qrCodeUrl;

    @Schema(description = "When this setup session (setupToken + secret) expires — matches the actual Redis TTL",
            example = "2026-08-12T21:30:00+07:00")
    private OffsetDateTime expiresAt;
}
