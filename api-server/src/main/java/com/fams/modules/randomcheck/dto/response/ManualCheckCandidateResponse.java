package com.fams.modules.randomcheck.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class ManualCheckCandidateResponse {

    private UUID employeeId;
    private String employeeName;
    private String employeeCode;
    private UUID checkinId;
    private OffsetDateTime checkInAt;
    private UUID shiftId;
    private String shiftName;
}
