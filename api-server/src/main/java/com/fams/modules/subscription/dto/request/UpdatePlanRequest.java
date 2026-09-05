package com.fams.modules.subscription.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "Partial update for a subscription plan. All fields are optional.")
public class UpdatePlanRequest {

    @Schema(description = "New display name (2–100 chars)", example = "Pro Plan")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    private String displayName;

    @Schema(description = "New description (max 2000 chars)", example = "Updated description.")
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Schema(description = "New monthly price in whole VND", example = "30000")
    @DecimalMin(value = "0.00", message = "Monthly price must be non-negative")
    @Digits(integer = 8, fraction = 0, message = "Monthly price must be a whole VND amount")
    private BigDecimal priceMonthly;

    @Schema(description = "New yearly price in whole VND", example = "300000")
    @DecimalMin(value = "0.00", message = "Yearly price must be non-negative")
    @Digits(integer = 8, fraction = 0, message = "Yearly price must be a whole VND amount")
    private BigDecimal priceYearly;

    @Schema(description = "New sort order", example = "3")
    @Min(value = 0, message = "Sort order must be non-negative")
    private Integer sortOrder;

    @Schema(description = "Set to false to hide the plan from new subscribers", example = "true")
    private Boolean isActive;

    @Schema(description = "Issue #8: required when setting isActive=false and tenants are still " +
            "subscribed to this plan — they are migrated to this plan first. Ignored otherwise.")
    private UUID migrateToPlanId;
}
