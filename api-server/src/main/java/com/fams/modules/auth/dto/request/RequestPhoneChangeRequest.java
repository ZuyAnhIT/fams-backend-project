package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Add or change the account's phone number (step 1 of 2 — sends an OTP via SMS)")
public class RequestPhoneChangeRequest {

    @Schema(description = "New phone number in E.164 format", example = "+84912345678")
    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Số điện thoại không hợp lệ")
    private String phone;
}
