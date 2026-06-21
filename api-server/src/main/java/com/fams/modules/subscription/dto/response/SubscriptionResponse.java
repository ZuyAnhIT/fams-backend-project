package com.fams.modules.subscription.dto.response;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Tenant subscription details")
public class SubscriptionResponse {

    @Schema(description = "Subscription UUID")
    private UUID id;

    @Schema(description = "UUID of the subscribed tenant")
    private UUID tenantId;

    @Schema(description = "UUID of the subscribed plan")
    private UUID planId;

    @Schema(description = "Internal plan key", example = "pro-plan")
    private String planName;

    @Schema(description = "Display name of the plan", example = "Pro Plan")
    private String planDisplayName;

    @Schema(description = "Current subscription status", example = "ACTIVE")
    private SubscriptionStatus status;

    @Schema(description = "Billing cycle: MONTHLY or YEARLY", example = "MONTHLY")
    private BillingCycle billingCycle;

    @Schema(description = "Subscription start date (UTC)")
    private OffsetDateTime startedAt;

    @Schema(description = "Subscription expiry date (null = never expires)")
    private OffsetDateTime expiresAt;

    @Schema(description = "Cancellation date (null if not cancelled)")
    private OffsetDateTime cancelledAt;

    @Schema(description = "Record creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
