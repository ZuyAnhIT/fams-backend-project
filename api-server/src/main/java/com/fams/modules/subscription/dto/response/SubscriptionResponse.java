package com.fams.modules.subscription.dto.response;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class SubscriptionResponse {
    private UUID id;
    private UUID tenantId;
    private UUID planId;
    private String planName;
    private String planDisplayName;
    private SubscriptionStatus status;
    private BillingCycle billingCycle;
    private OffsetDateTime startedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
