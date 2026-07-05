package com.fams.modules.randomcheck.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCheckModeRequest {

    @Schema(
        description = "Verification mode to apply for random checks. " +
                      "location_only: GPS geofence check only. " +
                      "location_face: GPS + async AI face match (requires employee to have an enrolled Face ID; " +
                      "employees without an enrolled profile will automatically receive a face_fail violation). " +
                      "location_face_liveness: GPS + face match + liveness anti-spoofing (same enrollment requirement).",
        example = "location_face",
        allowableValues = {"location_only", "location_face", "location_face_liveness"}
    )
    @NotBlank(message = "check_mode is required")
    @Pattern(
        regexp = "location_only|location_face|location_face_liveness",
        message = "check_mode must be one of: location_only, location_face, location_face_liveness"
    )
    private String checkMode;
}
