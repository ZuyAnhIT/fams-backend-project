package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "JWT token pair trả về khi đăng nhập thành công")
public class LoginResponse {

    // ─── Identity ─────────────────────────────────────────────────────────────

    @Schema(
        description = "UUID của user vừa đăng nhập",
        example = "550e8400-e29b-41d4-a716-446655440000"
    )
    private UUID userId;

    @Schema(
        description = "Tenant đang active trong session này. Null nếu user chưa thuộc tenant nào.",
        example = "660e8400-e29b-41d4-a716-446655440001"
    )
    private UUID activeTenantId;

    // ─── Tokens ───────────────────────────────────────────────────────────────

    @Schema(description = "Short-lived JWT access token (15 min)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "Long-lived refresh token dùng để lấy access token mới", example = "a3f1c2...")
    private String refreshToken;

    @Schema(description = "Luôn là Bearer", example = "Bearer")
    @Builder.Default
    private String tokenType = "Bearer";

    @Schema(description = "Thời gian sống của access token, tính bằng giây", example = "900")
    private long expiresIn;

    // ─── TOTP gate ────────────────────────────────────────────────────────────

    @Schema(
        description = "true khi tài khoản bật TOTP — lúc này accessToken/refreshToken là null, "
            + "chỉ có pendingToken"
    )
    @Builder.Default
    private boolean totpRequired = false;

    @Schema(
        description = "Token tạm thời đổi lấy token thật sau khi xác minh TOTP "
            + "(chỉ có mặt khi totpRequired=true)",
        example = "3f2a1b4c-..."
    )
    private String pendingToken;
}