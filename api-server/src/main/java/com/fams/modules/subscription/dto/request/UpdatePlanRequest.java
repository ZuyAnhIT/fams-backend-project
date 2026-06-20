package com.fams.modules.subscription.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdatePlanRequest {

    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    private String displayName;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @DecimalMin(value = "0.00", message = "Monthly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Monthly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceMonthly;

    @DecimalMin(value = "0.00", message = "Yearly price must be non-negative")
    @Digits(integer = 8, fraction = 2, message = "Yearly price must have at most 8 integer digits and 2 decimal places")
    private BigDecimal priceYearly;

    @Min(value = 0, message = "Sort order must be non-negative")
    private Integer sortOrder;

    private Boolean isActive;
}
