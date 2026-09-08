package com.fams.modules.subscription.service;

import java.time.OffsetDateTime;
import java.util.Map;

public interface PaymentGateway {

    boolean isConfigured();

    CreatedLink createPaymentLink(CreateLinkCommand command);

    VerifiedWebhook verifyWebhook(Map<String, Object> body);

    ProviderPayment getPayment(long orderCode);

    ProviderPayment cancelPayment(long orderCode, String reason);

    record CreateLinkCommand(
            long orderCode,
            long amount,
            String description,
            String planDisplayName,
            String buyerCompanyName,
            String buyerEmail,
            String returnUrl,
            String cancelUrl,
            OffsetDateTime expiresAt) {
    }

    record CreatedLink(
            String paymentLinkId,
            String checkoutUrl,
            String qrCode,
            String status) {
    }

    record VerifiedWebhook(
            long orderCode,
            long amount,
            String currency,
            String paymentLinkId,
            String reference,
            String code) {
    }

    record ProviderPayment(
            long orderCode,
            long expectedAmount,
            long amountPaid,
            String paymentLinkId,
            String status,
            String reference) {
    }
}
