package com.fams.modules.subscription.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.repository.BillingOrderRepository;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingOrderServiceTest {

    @Mock BillingOrderRepository orderRepository;
    @Mock PlanRepository planRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock TenantSubscriptionService subscriptionService;
    @Mock AuditLogService auditLogService;

    private BillingOrderService service;

    @BeforeEach
    void setUp() {
        service = new BillingOrderService(orderRepository, planRepository, tenantRepository,
                userRepository, paymentGateway, subscriptionService, auditLogService,
                "http://localhost:3000", 30);
    }

    @Test
    void verifiedPaidWebhookActivatesSubscriptionOnlyOnce() {
        BillingOrder order = pendingOrder();
        Map<String, Object> payload = Map.of("signature", "signed", "data", Map.of("orderCode", 100001));
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(100001, 500_000, "VND", "link-1", "bank-ref-1", "00"));
        when(orderRepository.findByOrderCodeForUpdate(100001L)).thenReturn(Optional.of(order));
        when(paymentGateway.getPayment(100001L)).thenReturn(
                new PaymentGateway.ProviderPayment(100001, 500_000, 500_000, "link-1", "PAID", "bank-ref-1"));

        service.processWebhook(payload);
        service.processWebhook(payload);

        assertThat(order.getStatus()).isEqualTo(BillingOrderStatus.PAID);
        assertThat(order.getAmountPaid()).isEqualTo(500_000);
        assertThat(order.getSubscriptionAppliedAt()).isNotNull();
        verify(subscriptionService, times(1)).activateFromPayment(
                eq(order.getTenantId()), eq(order.getPlanId()), eq(BillingCycle.MONTHLY),
                any(OffsetDateTime.class), eq(order.getId()));
    }

    @Test
    void signedUnknownWebhookIsAcknowledgedForPayosUrlConfirmation() {
        Map<String, Object> payload = Map.of("signature", "signed");
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(123, 2_000, "VND", "sample", "sample-ref", "00"));
        when(orderRepository.findByOrderCodeForUpdate(123L)).thenReturn(Optional.empty());

        service.processWebhook(payload);

        verify(paymentGateway, never()).getPayment(anyLong());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void providerAmountMismatchNeverActivatesSubscription() {
        BillingOrder order = pendingOrder();
        Map<String, Object> payload = Map.of("signature", "signed");
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(100001, 100_000, "VND", "link-1", "bank-ref-1", "00"));
        when(orderRepository.findByOrderCodeForUpdate(100001L)).thenReturn(Optional.of(order));
        when(paymentGateway.getPayment(100001L)).thenReturn(
                new PaymentGateway.ProviderPayment(100001, 100_000, 100_000, "link-1", "PAID", "bank-ref-1"));

        assertThatThrownBy(() -> service.processWebhook(payload))
                .isInstanceOf(InvalidPaymentWebhookException.class);
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void paymentDescriptionAlwaysFitsStrictPayosNineCharacterLimit() {
        assertThat(BillingOrderService.buildPayOsDescription(100_000L)).isEqualTo("F255S");
        assertThat(BillingOrderService.buildPayOsDescription(2_821_109_907_455L))
                .isEqualTo("FZZZZZZZZ")
                .hasSize(BillingOrderService.PAYOS_DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void paymentDescriptionRejectsInvalidOrUnrepresentableOrderCodes() {
        assertThatThrownBy(() -> BillingOrderService.buildPayOsDescription(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingOrderService.buildPayOsDescription(2_821_109_907_456L))
                .isInstanceOf(IllegalStateException.class);
    }

    private BillingOrder pendingOrder() {
        return BillingOrder.builder()
                .id(UUID.randomUUID())
                .orderCode(100001L)
                .tenantId(UUID.randomUUID())
                .planId(UUID.randomUUID())
                .planNameSnapshot("pro")
                .planDisplaySnapshot("Pro")
                .billingCycle(BillingCycle.MONTHLY)
                .amount(500_000L)
                .amountPaid(0L)
                .currency("VND")
                .status(BillingOrderStatus.PENDING)
                .paymentLinkId("link-1")
                .createdBy(UUID.randomUUID())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
