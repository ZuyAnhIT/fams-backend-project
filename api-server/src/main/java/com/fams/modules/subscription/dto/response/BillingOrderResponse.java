package com.fams.modules.subscription.dto.response;

import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.entity.BillingOrder.BillingInvoiceStatus;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class BillingOrderResponse {
    private UUID id;
    private Long orderCode;
    private UUID tenantId;
    private String tenantName;
    private UUID planId;
    private String planName;
    private String planDisplayName;
    private BillingCycle billingCycle;
    private Long amount;
    private Long amountPaid;
    private String currency;
    private BillingOrderStatus status;
    private String paymentLinkId;
    private String checkoutUrl;
    private String qrCode;
    private String paymentReference;
    private String providerStatus;
    private String failureReason;
    private OffsetDateTime expiresAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime subscriptionAppliedAt;
    private boolean paymentReceiptAvailable;
    private String paymentReceiptNumber;
    private OffsetDateTime paymentReceiptIssuedAt;
    private BillingInvoiceStatus invoiceStatus;
    private String invoiceNumber;
    private OffsetDateTime invoiceIssuedAt;
    private String invoiceLookupUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
