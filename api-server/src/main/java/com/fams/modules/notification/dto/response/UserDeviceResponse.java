package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Registered device record")
public class UserDeviceResponse {

  @Schema(description = "Device record UUID")
  private UUID id;

  @Schema(description = "User UUID")
  private UUID userId;

  @Schema(description = "FCM device token")
  private String deviceToken;

  @Schema(description = "Platform (FCM, APNS)", example = "FCM")
  private String platform;

  @Schema(description = "Registration timestamp")
  private OffsetDateTime createdAt;
}
