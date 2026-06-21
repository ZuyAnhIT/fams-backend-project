package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Verify an OTP code and receive JWT tokens")
public class VerifyOtpRequest {

    @Schema(description = "Phone number in E.164 format", example = "+84912345678")
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Invalid phone number format")
    private String phone;

    @Schema(description = "6-digit OTP code received via SMS", example = "123456")
    @NotBlank(message = "OTP code is required")
    @Pattern(regexp = "^\\d{6}$", message = "OTP must be exactly 6 digits")
    private String code;

    @Schema(description = "Unique device identifier for multi-device token tracking", example = "device-abc-123")
    private String deviceId;
}
