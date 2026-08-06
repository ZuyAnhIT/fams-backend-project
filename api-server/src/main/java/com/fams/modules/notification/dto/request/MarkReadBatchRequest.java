package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Schema(description = "Request body for marking a specific set of notifications as read")
public class MarkReadBatchRequest {

    @NotEmpty(message = "notificationIds must not be empty")
    @Schema(description = "Notification UUIDs to mark as read")
    private List<UUID> notificationIds;
}
