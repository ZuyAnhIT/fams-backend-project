package com.fams.modules.subscription.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Create a new SaaS subscription plan")
public class CreatePlanRequest {

    @Schema(description = "Internal plan key — lowercase, digits, hyphens, underscores only (2–50 chars)", example = "pro-plan")
    @NotBlank(message = "Plan name is required")
    @Size(min = 2, max = 50, message = "Name must be between 2 and 50 characters")
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "Name must contain only lowercase letters, digits, hyphens, or underscores")
    private String name;

    @Schema(description = "Display name shown to customers (2–100 chars)", example = "Pro Plan")
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    private String displayName;

    @Schema(description = "Plan description (max 2000 chars)", example = "Ideal for growing teams with up to 500 employees.")
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Schema(description = "Monthly price (non-negative, max 8 integer digits + 2 decimal places)", example = "49.99")
    @NotNull(message = "Monthly price is required")
    @DecimalMin(value = "0.00", message = "Monthly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Monthly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceMonthly;

    @Schema(description = "Yearly price (non-negative)", example = "499.99")
    @NotNull(message = "Yearly price is required")
    @DecimalMin(value = "0.00", message = "Yearly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Yearly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceYearly;

    @Schema(description = "Display sort order (ascending), 0 by default", example = "2")
    @Min(value = 0, message = "Sort order must be non-negative")
    private int sortOrder;
}
