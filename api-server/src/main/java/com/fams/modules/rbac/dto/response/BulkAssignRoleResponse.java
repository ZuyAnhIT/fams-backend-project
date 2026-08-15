package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Per-user outcome of a bulk role assignment — partial failure is normal and expected "
        + "(e.g. one user already had the role), the whole batch never rolls back over a single failure.")
public class BulkAssignRoleResponse {

    private List<Result> results;
    private int successCount;
    private int failureCount;

    @Data
    @Builder
    @Schema(description = "Outcome for one user in the batch")
    public static class Result {
        private UUID userId;
        private boolean success;
        private UUID userRoleId;
        private String message;
    }
}
