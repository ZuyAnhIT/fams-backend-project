package com.fams.modules.checkin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OverrideCheckinRequest {

    @NotBlank(message = "status is required")
    @Pattern(regexp = "valid|rejected", message = "status must be 'valid' or 'rejected'")
    private String status;

    @NotBlank(message = "reason is required")
    private String reason;
}
