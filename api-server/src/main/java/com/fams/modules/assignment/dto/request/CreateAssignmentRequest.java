package com.fams.modules.assignment.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Data
@Schema(description = "Request body for assigning an employee to a site")
public class CreateAssignmentRequest {

    @Schema(description = "Employee UUID to assign", required = true)
    @NotNull(message = "employeeId is required")
    private UUID employeeId;

    @Schema(description = "Shift template UUID — optional; links the assignment to a specific shift schedule")
    private UUID shiftId;

    @Schema(description = "Assignment start date (yyyy-MM-dd)", example = "2026-07-01", required = true)
    @NotNull(message = "startDate is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Assignment end date (yyyy-MM-dd), inclusive. Omit for open-ended assignments.",
            example = "2026-12-31")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(description = "Issue #14: restrict the assignment to specific weekdays only " +
            "(e.g. [\"MONDAY\",\"WEDNESDAY\",\"FRIDAY\"]). Omit for every day in the date range (default).",
            example = "[\"MONDAY\",\"WEDNESDAY\",\"FRIDAY\"]")
    private Set<DayOfWeek> daysOfWeek;

    @Schema(description = "Employee's role at the site: 'worker' (default) or 'supervisor'",
            example = "worker", defaultValue = "worker", allowableValues = {"worker", "supervisor"})
    @Pattern(regexp = "^(worker|supervisor)$", message = "Role must be 'worker' or 'supervisor'")
    private String role = "worker";

    @Schema(description = "Optional notes about the assignment")
    private String notes;
}
