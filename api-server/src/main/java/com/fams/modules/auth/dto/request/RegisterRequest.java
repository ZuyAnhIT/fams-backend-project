package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Yêu cầu đăng ký tài khoản mới. Cung cấp email HOẶC phone, không bắt buộc cả hai.")
public class RegisterRequest {

    @Schema(
        description = "Địa chỉ email. Bắt buộc nếu không cung cấp phone.",
        example = "alice@acme.com"
    )
    @Email(message = "Định dạng email không hợp lệ")
    private String email;

    @Schema(
        description = "Số điện thoại theo chuẩn E.164. Bắt buộc nếu không cung cấp email.",
        example = "+84912345678"
    )
    @Pattern(
        regexp = "^\\+?[1-9]\\d{7,14}$",
        message = "Số điện thoại không hợp lệ (phải theo định dạng quốc tế, VD: +84912345678)"
    )
    private String phone;

    @Schema(description = "Mật khẩu — tối thiểu 8 ký tự, phải có chữ hoa, chữ thường và số", example = "S3cur3P@ss")
    @NotBlank(message = "Mật khẩu là bắt buộc")
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "Mật khẩu phải chứa ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ số"
    )
    private String password;

    @Schema(description = "Họ tên hiển thị trong hệ thống", example = "Nguyễn Văn A")
    @NotBlank(message = "Tên hiển thị là bắt buộc")
    @Size(max = 100, message = "Tên hiển thị không được vượt quá 100 ký tự")
    private String displayName;

    @Schema(description = "ID thiết bị để quản lý session", example = "device-abc-123")
    private String deviceId;

    // ── Phone OTP flow ──────────────────────────────────────────────────────
    // Khi đăng ký bằng phone:
    //   Bước 1: Gọi POST /auth/register/send-otp { phone }  → nhận OTP qua SMS
    //   Bước 2: Gọi POST /auth/register { phone, password, displayName, otpCode }
    //
    // Khi đăng ký bằng email: không cần otpCode — hệ thống gửi link verify email.

    @Schema(
        description = "Mã OTP 6 số nhận qua SMS. Bắt buộc khi đăng ký bằng phone.",
        example = "123456"
    )
    @Pattern(
        regexp = "^\\d{6}$",
        message = "Mã OTP phải là 6 chữ số"
    )
    private String otpCode;
}