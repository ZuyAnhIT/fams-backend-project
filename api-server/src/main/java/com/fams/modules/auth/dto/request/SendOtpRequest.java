package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "Yêu cầu gửi OTP xác thực số điện thoại (bước 1 đăng ký bằng phone)")
public class SendOtpRequest {

    @Schema(
        description = "Số điện thoại theo chuẩn E.164",
        example = "+84912345678"
    )
    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(
        regexp = "^\\+?[1-9]\\d{7,14}$",
        message = "Số điện thoại không hợp lệ (VD: +84912345678)"
    )
    private String phone;
}