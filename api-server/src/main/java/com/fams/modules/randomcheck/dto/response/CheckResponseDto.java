package com.fams.modules.randomcheck.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class CheckResponseDto {
    private UUID id;
    private UUID scheduledCheckId;
    private UUID employeeId;
    private OffsetDateTime respondedAt;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal accuracyMeters;
    private boolean locationVerified;
    private Boolean faceVerified;
    private Boolean livenessVerified;
    private Double faceVerifyScore;
    private String outcome;
    private String failureReason;
    private boolean hasPhotoEvidence;
    private OffsetDateTime createdAt;
}
