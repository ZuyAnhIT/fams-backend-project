package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Đăng nhập bằng email hoặc số điện thoại + mật khẩu")
public class LoginRequest {

    @Schema(
        description = "Email hoặc số điện thoại đã đăng ký",
        example = "alice@acme.com"
    )
    @NotBlank(message = "Vui lòng nhập email hoặc số điện thoại")
    private String identifier;

    @Schema(description = "Mật khẩu tài khoản", example = "S3cur3P@ss")
    @NotBlank(message = "Mật khẩu là bắt buộc")
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    private String password;

    @Schema(description = "ID thiết bị để quản lý session theo thiết bị", example = "device-abc-123")
    private String deviceId;
}