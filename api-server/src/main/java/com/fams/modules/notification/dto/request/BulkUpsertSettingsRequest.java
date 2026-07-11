package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Request to bulk upsert notification settings")
public class BulkUpsertSettingsRequest {

    @NotEmpty
    @Valid
    @Schema(description = "List of notification settings to upsert", required = true)
    private List<UpsertNotificationSettingRequest> settings;
}
