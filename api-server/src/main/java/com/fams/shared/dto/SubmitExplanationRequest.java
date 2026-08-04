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

    /** @deprecated Public/arbitrary URLs are rejected. Upload evidence with multipart/form-data. */
    @Deprecated
    private String photoUrl;
}
