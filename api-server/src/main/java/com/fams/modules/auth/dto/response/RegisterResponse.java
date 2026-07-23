package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Kết quả đăng ký tài khoản")
public class RegisterResponse {

    @Schema(description = "UUID của user vừa tạo", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    @Schema(
        description = "true = đã gửi email xác thực, user phải verify trước khi đăng nhập",
        example = "true"
    )
    private boolean emailVerificationRequired;

    @Schema(
        description = "true = số điện thoại đã xác thực qua OTP, tài khoản đã sẵn sàng đăng nhập",
        example = "false"
    )
    private boolean phoneVerified;

    @Schema(
        description = "Thông báo trạng thái đăng ký",
        example = "Đăng ký thành công. Vui lòng kiểm tra email để xác thực tài khoản."
    )
    private String message;
}