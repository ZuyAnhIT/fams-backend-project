package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to register a device FCM token for push notifications")
public class RegisterDeviceRequest {

  @NotBlank
  @Schema(description = "FCM device token obtained from the Firebase SDK", example = "dGhpcyBpcyBhIGZha2UgdG9rZW4...")
  private String deviceToken;

  @Schema(description = "Device platform (default: FCM)", example = "FCM", allowableValues = {"FCM", "APNS"})
  private String platform;
}
