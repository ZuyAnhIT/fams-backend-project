package com.fams.modules.subscription.controller;

import com.fams.modules.subscription.dto.response.PayOSWebhookResponse;
import com.fams.modules.subscription.service.BillingOrderService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Hidden
@RestController
public class PayOSWebhookController {

    private final BillingOrderService billingOrderService;

    public PayOSWebhookController(BillingOrderService billingOrderService) {
        this.billingOrderService = billingOrderService;
    }

    @PostMapping("/api/v1/payments/payos/webhook")
    public ResponseEntity<PayOSWebhookResponse> receive(@RequestBody Map<String, Object> body) {
        billingOrderService.processWebhook(body);
        return ResponseEntity.ok(PayOSWebhookResponse.builder().code("00").desc("success").build());
    }
}
