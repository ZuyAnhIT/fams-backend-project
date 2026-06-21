package com.fams.modules.subscription.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Partial update for a subscription plan. All fields are optional.")
public class UpdatePlanRequest {

    @Schema(description = "New display name (2–100 chars)", example = "Pro Plan")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    private String displayName;

    @Schema(description = "New description (max 2000 chars)", example = "Updated description.")
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Schema(description = "New monthly price", example = "59.99")
    @DecimalMin(value = "0.00", message = "Monthly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Monthly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceMonthly;

    @Schema(description = "New yearly price", example = "599.99")
    @DecimalMin(value = "0.00", message = "Yearly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Yearly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceYearly;

    @Schema(description = "New sort order", example = "3")
    @Min(value = 0, message = "Sort order must be non-negative")
    private Integer sortOrder;

    @Schema(description = "Set to false to hide the plan from new subscribers", example = "true")
    private Boolean isActive;
}
