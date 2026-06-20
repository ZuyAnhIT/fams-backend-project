package com.fams.modules.subscription.dto.request;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class UpdateSubscriptionRequest {

    private UUID planId;
    private BillingCycle billingCycle;
    private SubscriptionStatus status;
    private OffsetDateTime startedAt;
    private OffsetDateTime expiresAt;

    /** When true, sets expiresAt to null (never expires) */
    private boolean clearExpiresAt;
}
