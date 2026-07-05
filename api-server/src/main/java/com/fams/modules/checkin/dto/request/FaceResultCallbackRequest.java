package com.fams.modules.checkin.dto.request;

import lombok.Data;

import java.util.UUID;

@Data
public class FaceResultCallbackRequest {
    private UUID sourceId;
    private UUID tenantId;
    private String sourceType;
    private Boolean faceVerified;
    private Boolean livenessVerified;
    private Double faceVerifyScore;
    private String errorCode;
}
