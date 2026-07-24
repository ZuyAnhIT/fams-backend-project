package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Confirm a phone number change with the OTP sent by /auth/profile/phone/request-change (step 2 of 2)")
public class ConfirmPhoneChangeRequest {

    @Schema(description = "Same phone number passed to request-change", example = "+84912345678")
    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @Schema(description = "6-digit OTP received via SMS", example = "123456")
    @NotBlank(message = "Mã OTP là bắt buộc")
    @Pattern(regexp = "^\\d{6}$", message = "Mã OTP phải là 6 chữ số")
    private String otpCode;
}
