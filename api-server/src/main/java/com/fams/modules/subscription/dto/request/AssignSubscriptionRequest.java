package com.fams.modules.subscription.dto.request;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AssignSubscriptionRequest {

    @NotNull(message = "planId is required")
    private UUID planId;

    @NotNull(message = "billingCycle is required")
    private BillingCycle billingCycle;

    /** Defaults to now if omitted */
    private OffsetDateTime startedAt;

    /** null = never expires */
    private OffsetDateTime expiresAt;

    /** Defaults to ACTIVE if omitted */
    private SubscriptionStatus status;
}
