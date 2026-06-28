package com.fams.shared.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SubmitExplanationRequest {

    @NotBlank(message = "note is required")
    private String note;

    private String photoUrl;
}
