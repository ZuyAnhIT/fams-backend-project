package com.fams.modules.subscription.dto.request;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Schema(description = "Partial update for a tenant subscription. All fields are optional.")
public class UpdateSubscriptionRequest {

    @Schema(description = "Change the plan (UUID of the new plan)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID planId;

    @Schema(description = "New billing cycle: MONTHLY or YEARLY", example = "YEARLY")
    private BillingCycle billingCycle;

    @Schema(description = "New subscription status", example = "CANCELLED")
    private SubscriptionStatus status;

    @Schema(description = "New subscription start date")
    private OffsetDateTime startedAt;

    @Schema(description = "New expiry date (ignored when clearExpiresAt=true)")
    private OffsetDateTime expiresAt;

    @Schema(description = "When true, sets expiresAt to null (subscription never expires)", example = "false")
    private boolean clearExpiresAt;
}
