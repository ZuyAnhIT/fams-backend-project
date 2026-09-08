package com.fams.modules.subscription.dto.request;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateBillingOrderRequest {

    @NotNull(message = "Plan is required")
    private UUID planId;

    @NotNull(message = "Billing cycle is required")
    private BillingCycle billingCycle;
}
