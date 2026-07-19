package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A managed department within a tenant")
public class DepartmentResponse {

    @Schema(description = "Department UUID")
    private UUID id;

    @Schema(description = "Owning tenant UUID")
    private UUID tenantId;

    @Schema(description = "Department name", example = "Engineering")
    private String name;

    @Schema(description = "Department description", example = "Software development team")
    private String description;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
