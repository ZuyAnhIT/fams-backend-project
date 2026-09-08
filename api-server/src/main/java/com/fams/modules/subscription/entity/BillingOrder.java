package com.fams.modules.subscription.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "billing_orders")
public class BillingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "order_code", nullable = false, unique = true)
    private Long orderCode;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Buyer name frozen at checkout time so historical payment documents remain stable. */
    @Column(name = "tenant_name_snapshot", nullable = false, length = 255)
    private String tenantNameSnapshot;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "plan_name_snapshot", nullable = false, length = 50)
    private String planNameSnapshot;

    @Column(name = "plan_display_snapshot", nullable = false, length = 100)
    private String planDisplaySnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 10)
    private TenantSubscription.BillingCycle billingCycle;

    /** VND has no fractional minor unit in the payOS payment-link contract. */
    @Column(nullable = false)
    private Long amount;

    @Column(name = "amount_paid", nullable = false)
    private Long amountPaid;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingOrderStatus status;

    @Column(name = "payment_link_id", length = 100)
    private String paymentLinkId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "payment_reference", length = 150)
    private String paymentReference;

    @Column(name = "provider_status", length = 30)
    private String providerStatus;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "subscription_applied_at")
    private OffsetDateTime subscriptionAppliedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_status", nullable = false, length = 30)
    private BillingInvoiceStatus invoiceStatus;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "invoice_issued_at")
    private OffsetDateTime invoiceIssuedAt;

    @Column(name = "invoice_lookup_url", columnDefinition = "TEXT")
    private String invoiceLookupUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (currency == null) currency = "VND";
        if (amountPaid == null) amountPaid = 0L;
        if (status == null) status = BillingOrderStatus.CREATING;
        if (invoiceStatus == null) invoiceStatus = BillingInvoiceStatus.NOT_ELIGIBLE;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isOpen() {
        return status == BillingOrderStatus.CREATING
                || status == BillingOrderStatus.PENDING
                || status == BillingOrderStatus.PROCESSING
                || status == BillingOrderStatus.UNDERPAID;
    }

    public enum BillingOrderStatus {
        CREATING, PENDING, PROCESSING, UNDERPAID, PAID, CANCELLED, EXPIRED, FAILED
    }

    public enum BillingInvoiceStatus {
        /** No money was confirmed, therefore no invoice is created for this order. */
        NOT_ELIGIBLE,
        /** Money was received but the order is underpaid and needs accounting review. */
        PAYMENT_REVIEW,
        /** Payment succeeded; waiting for a licensed e-invoice provider to issue the tax invoice. */
        PENDING_ISSUANCE,
        ISSUED,
        FAILED
    }
}
