package com.fams.modules.subscription.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PayOSWebhookResponse {
    private String code;
    private String desc;
}
