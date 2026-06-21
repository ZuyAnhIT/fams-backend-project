package com.fams.modules.subscription.dto.request;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Schema(description = "Assign a subscription plan to a tenant")
public class AssignSubscriptionRequest {

    @Schema(description = "UUID of the plan to subscribe to", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "planId is required")
    private UUID planId;

    @Schema(description = "Billing cycle: MONTHLY or YEARLY", example = "MONTHLY")
    @NotNull(message = "billingCycle is required")
    private BillingCycle billingCycle;

    @Schema(description = "Subscription start date (defaults to now if omitted)")
    private OffsetDateTime startedAt;

    @Schema(description = "Subscription expiry date (null = never expires)")
    private OffsetDateTime expiresAt;

    @Schema(description = "Initial status (defaults to ACTIVE if omitted)", example = "ACTIVE")
    private SubscriptionStatus status;
}
