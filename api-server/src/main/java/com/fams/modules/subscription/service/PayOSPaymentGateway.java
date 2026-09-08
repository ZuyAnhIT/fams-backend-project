package com.fams.modules.subscription.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vn.payos.PayOS;
import vn.payos.exception.PayOSException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.v2.paymentRequests.Transaction;
import vn.payos.model.webhooks.WebhookData;

import java.util.Comparator;
import java.util.Map;

@Slf4j
@Component
public class PayOSPaymentGateway implements PaymentGateway {

    private final String clientId;
    private final String apiKey;
    private final String checksumKey;
    private volatile PayOS client;

    public PayOSPaymentGateway(
            @Value("${app.billing.payos.client-id:}") String clientId,
            @Value("${app.billing.payos.api-key:}") String apiKey,
            @Value("${app.billing.payos.checksum-key:}") String checksumKey) {
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.checksumKey = checksumKey;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(clientId)
                && StringUtils.hasText(apiKey)
                && StringUtils.hasText(checksumKey);
    }

    @Override
    public CreatedLink createPaymentLink(CreateLinkCommand command) {
        try {
            if (!StringUtils.hasText(command.description())
                    || command.description().length() > BillingOrderService.PAYOS_DESCRIPTION_MAX_LENGTH) {
                throw new IllegalArgumentException("payOS payment description must contain 1 to 9 characters");
            }
            PaymentLinkItem item = PaymentLinkItem.builder()
                    .name(command.planDisplayName())
                    .quantity(1)
                    .price(command.amount())
                    .build();
            var request = CreatePaymentLinkRequest.builder()
                    .orderCode(command.orderCode())
                    .amount(command.amount())
                    .description(command.description())
                    .item(item)
                    .buyerCompanyName(command.buyerCompanyName())
                    .buyerEmail(command.buyerEmail())
                    .returnUrl(command.returnUrl())
                    .cancelUrl(command.cancelUrl())
                    .expiredAt(command.expiresAt().toEpochSecond())
                    .build();
            var response = client().paymentRequests().create(request);
            return new CreatedLink(
                    response.getPaymentLinkId(),
                    response.getCheckoutUrl(),
                    response.getQrCode(),
                    response.getStatus().getValue());
        } catch (PayOSException | IllegalArgumentException ex) {
            throw new PaymentGatewayException("payOS create payment link failed", ex);
        }
    }

    @Override
    public VerifiedWebhook verifyWebhook(Map<String, Object> body) {
        try {
            WebhookData data = client().webhooks().verify(body);
            return new VerifiedWebhook(
                    data.getOrderCode(), data.getAmount(), data.getCurrency(),
                    data.getPaymentLinkId(), data.getReference(), data.getCode());
        } catch (PayOSException | IllegalArgumentException ex) {
            throw new InvalidPaymentWebhookException("payOS webhook signature verification failed", ex);
        }
    }

    @Override
    public ProviderPayment getPayment(long orderCode) {
        try {
            return toProviderPayment(client().paymentRequests().get(orderCode));
        } catch (PayOSException | IllegalArgumentException ex) {
            throw new PaymentGatewayException("payOS get payment link failed", ex);
        }
    }

    @Override
    public ProviderPayment cancelPayment(long orderCode, String reason) {
        try {
            return toProviderPayment(client().paymentRequests().cancel(orderCode, reason));
        } catch (PayOSException | IllegalArgumentException ex) {
            throw new PaymentGatewayException("payOS cancel payment link failed", ex);
        }
    }

    private ProviderPayment toProviderPayment(PaymentLink link) {
        String reference = link.getTransactions() == null ? null : link.getTransactions().stream()
                .filter(tx -> StringUtils.hasText(tx.getReference()))
                .max(Comparator.comparing(Transaction::getTransactionDateTime,
                        Comparator.nullsLast(String::compareTo)))
                .map(Transaction::getReference)
                .orElse(null);
        return new ProviderPayment(
                link.getOrderCode(), link.getAmount(), link.getAmountPaid(), link.getId(),
                link.getStatus().getValue(), reference);
    }

    private PayOS client() {
        if (!isConfigured()) {
            throw new PaymentGatewayException("payOS credentials are not configured", null);
        }
        PayOS local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = new PayOS(clientId, apiKey, checksumKey);
                    client = local;
                }
            }
        }
        return local;
    }
}
