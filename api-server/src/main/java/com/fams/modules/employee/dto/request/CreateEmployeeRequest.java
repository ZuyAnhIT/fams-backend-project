package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateEmployeeRequest {

    @Schema(description = "First name", example = "John")
    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @Schema(description = "Work email (optional)", example = "john.doe@example.com")
    @Email(message = "Must be a valid email address")
    private String email;

    @Schema(description = "Phone number (optional)", example = "+84901234567")
    @Size(max = 30)
    private String phone;

    @Schema(description = "Internal employee code — must be unique within the tenant (optional)", example = "EMP-001")
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z0-9\\-_]*$", message = "Employee code may only contain letters, digits, hyphens, and underscores")
    private String employeeCode;

    @Schema(description = "Job position / title (optional)", example = "Site Engineer")
    @Size(max = 100)
    private String position;

    @Schema(description = "Department or team (optional)", example = "Construction")
    @Size(max = 100)
    private String department;

    @Schema(description = "Date of hire (optional)", example = "2024-01-15")
    private LocalDate hiredDate;

    @Schema(description = "Avatar image URL (optional)")
    private String avatarUrl;
}
