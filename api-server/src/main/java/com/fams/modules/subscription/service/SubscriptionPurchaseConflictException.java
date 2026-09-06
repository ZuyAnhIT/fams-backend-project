package com.fams.modules.subscription.service;

import com.fams.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** Raised when checkout would duplicate a subscription period that is already active. */
public class SubscriptionPurchaseConflictException extends BusinessException {

    public SubscriptionPurchaseConflictException(String planDisplayName) {
        super(
                "SUBSCRIPTION_PLAN_ALREADY_ACTIVE",
                "Doanh nghiệp đang sử dụng gói " + planDisplayName
                        + ". Không thể thanh toán lại cùng gói khi gói hiện tại còn hiệu lực.",
                HttpStatus.CONFLICT,
                "Tenant already has an effective active subscription for plan " + planDisplayName);
    }
}
