package com.fams.shared.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceStatusDto {

    private String employeeId;
    private String status;
    private OffsetDateTime enrolledAt;
    private boolean consentGiven;
}
