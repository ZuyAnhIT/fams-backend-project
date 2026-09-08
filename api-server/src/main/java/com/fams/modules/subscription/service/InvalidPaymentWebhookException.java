package com.fams.modules.subscription.service;

import com.fams.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidPaymentWebhookException extends BusinessException {
    public InvalidPaymentWebhookException(String technicalMessage, Throwable cause) {
        super("INVALID_PAYMENT_WEBHOOK",
                "Dữ liệu webhook thanh toán không hợp lệ.",
                HttpStatus.BAD_REQUEST,
                technicalMessage);
        if (cause != null) initCause(cause);
    }
}
