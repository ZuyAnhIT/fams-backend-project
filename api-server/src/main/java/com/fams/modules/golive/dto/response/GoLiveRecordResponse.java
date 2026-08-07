package com.fams.modules.golive.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A go-live checklist record for a tenant")
public class GoLiveRecordResponse {

    private UUID id;
    private UUID tenantId;
    private String tenantName;
    private String environment;
    private String buildVersion;

    @Schema(description = "DRAFT, APPROVED, or REJECTED")
    private String status;

    private List<Map<String, Object>> steps;

    @Schema(description = "Tester who ran the checklist")
    private UUID performedBy;
    private String performedByName;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    @Schema(description = "Who signed off (null until approved/rejected)")
    private UUID approvedBy;
    private String approvedByName;
    private OffsetDateTime approvedAt;
    private String approvalNote;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
