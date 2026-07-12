package com.fams.modules.checkin.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class OfflineCheckinRequest {

    @NotNull
    private UUID assignmentId;

    @NotNull
    private OffsetDateTime checkinAt;

    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double lat;

    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double lon;

    private Double accuracy;

    private String facePhotoBase64;

    @NotNull
    private UUID clientNonce;
}
