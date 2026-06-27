package com.fams.modules.assignment.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Request body for updating an assignment. All fields optional — only provided fields are changed.")
public class UpdateAssignmentRequest {

    @Schema(description = "Shift template UUID. Pass null explicitly to unlink the shift.")
    private UUID shiftId;

    private boolean clearShift = false;

    @Schema(description = "New start date (yyyy-MM-dd)", example = "2026-07-01")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "New end date (yyyy-MM-dd, inclusive). Pass null to make open-ended.", example = "2026-12-31")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private boolean clearEndDate = false;

    @Schema(description = "Employee's role: worker or supervisor", example = "worker",
            allowableValues = {"worker", "supervisor"})
    @Pattern(regexp = "^(worker|supervisor)$", message = "Role must be 'worker' or 'supervisor'")
    private String role;

    @Schema(description = "Optional notes")
    private String notes;

    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }

    public boolean isClearShift() { return clearShift; }
    public void setClearShift(boolean clearShift) { this.clearShift = clearShift; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public boolean isClearEndDate() { return clearEndDate; }
    public void setClearEndDate(boolean clearEndDate) { this.clearEndDate = clearEndDate; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
