package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "One active login session/device (Issue #6, docs/issues/ISSUES.md)")
public class SessionResponse {

    @Schema(description = "Session (refresh token) ID — pass this to DELETE /auth/sessions/{id} to log out this specific device")
    private UUID id;

    @Schema(description = "Client-supplied device identifier", example = "iphone-15-abc123")
    private String deviceId;

    @Schema(description = "User-Agent header captured at login, if available", example = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0...)")
    private String userAgent;

    @Schema(description = "IP address captured at login, if available", example = "203.0.113.42")
    private String ipAddress;

    @Schema(description = "When this session was first created")
    private OffsetDateTime createdAt;

    @Schema(description = "Most recent activity on this session (login or token refresh)")
    private OffsetDateTime lastUsedAt;

    @Schema(description = "When this session expires if not used again")
    private OffsetDateTime expiresAt;

    @Schema(description = "True if this is the session the current request is using")
    private boolean current;
}
