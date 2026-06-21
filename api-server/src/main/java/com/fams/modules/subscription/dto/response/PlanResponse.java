package com.fams.modules.subscription.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Subscription plan details")
public class PlanResponse {

    @Schema(description = "Plan UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Internal plan key", example = "pro-plan")
    private String name;

    @Schema(description = "Display name shown to customers", example = "Pro Plan")
    private String displayName;

    @Schema(description = "Plan description")
    private String description;

    @Schema(description = "Monthly price", example = "49.99")
    private BigDecimal priceMonthly;

    @Schema(description = "Yearly price", example = "499.99")
    private BigDecimal priceYearly;

    @Schema(description = "Display sort order (ascending)", example = "2")
    private int sortOrder;

    @Schema(description = "Whether this plan is available for new subscriptions")
    @JsonProperty("isActive")
    private boolean isActive;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
