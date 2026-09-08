package com.fams.modules.subscription.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelBillingOrderRequest {

    @Size(max = 250, message = "Cancellation reason must be at most 250 characters")
    private String reason;
}
