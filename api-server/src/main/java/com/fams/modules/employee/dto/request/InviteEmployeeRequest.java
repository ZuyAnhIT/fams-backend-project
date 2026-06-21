package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class InviteEmployeeRequest {

    @Schema(description = "Email address to invite", example = "john.doe@example.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @Schema(description = "Employee's first name (optional)", example = "John")
    private String firstName;

    @Schema(description = "Employee's last name (optional)", example = "Doe")
    private String lastName;

    @Schema(description = "Role UUID to assign when the invitation is accepted (optional)")
    private UUID roleId;
}
