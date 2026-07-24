package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Add or change the account's email address (step 1 of 2 — sends a verification link)")
public class RequestEmailChangeRequest {

    @Schema(description = "New email address", example = "alice@acme.com")
    @NotBlank(message = "Email là bắt buộc")
    @Email(message = "Định dạng email không hợp lệ")
    private String email;
}
