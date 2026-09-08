package com.fams.modules.subscription.service;

import com.fams.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class PaymentGatewayException extends BusinessException {
    public PaymentGatewayException(String technicalMessage, Throwable cause) {
        super("PAYMENT_GATEWAY_ERROR",
                "Không thể kết nối cổng thanh toán. Vui lòng thử lại sau.",
                HttpStatus.BAD_GATEWAY,
                technicalMessage);
        if (cause != null) initCause(cause);
    }
}
