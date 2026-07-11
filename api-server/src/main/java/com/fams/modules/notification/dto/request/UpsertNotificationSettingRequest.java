package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request to upsert a single notification setting")
public class UpsertNotificationSettingRequest {

    @Schema(description = "Event type identifier (required in bulk upsert; ignored in single PUT where path param is used)", example = "RANDOM_CHECK_DISPATCHED")
    private String eventType;

    @Schema(description = "Whether in-app notifications are enabled for this event type", example = "true")
    private Boolean inAppEnabled;

    @Schema(description = "Whether push notifications are enabled for this event type", example = "true")
    private Boolean pushEnabled;
}
