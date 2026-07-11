package com.fams.modules.notification.controller;

import com.fams.modules.notification.dto.request.BulkUpsertSettingsRequest;
import com.fams.modules.notification.dto.request.UpsertNotificationSettingRequest;
import com.fams.modules.notification.dto.response.UserNotificationSettingResponse;
import com.fams.modules.notification.service.UserNotificationSettingService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Notification Settings", description = "Personal notification preference settings")
@RestController
@RequestMapping("/api/v1/me/notification-settings")
public class UserNotificationSettingController {

    private final UserNotificationSettingService settingService;

    public UserNotificationSettingController(UserNotificationSettingService settingService) {
        this.settingService = settingService;
    }

    @Operation(
            summary = "Get all notification settings",
            description = "Returns all notification settings for the authenticated user. "
                    + "Event types with no setting row are implicitly enabled (default opt-in).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "List of notification settings returned",
                content = @Content(schema = @Schema(implementation = UserNotificationSettingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Unauthorized — valid JWT required")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<UserNotificationSettingResponse>>> getSettings(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get notification settings userId={}", userDetails.getUserId());
        List<UserNotificationSettingResponse> result = settingService.getSettings(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
            summary = "Upsert a single notification setting",
            description = "Creates or updates the notification setting for the authenticated user "
                    + "and the given event type. Missing boolean fields default to true.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Setting created or updated",
                content = @Content(schema = @Schema(implementation = UserNotificationSettingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Unauthorized — valid JWT required")
    })
    @PutMapping("/{eventType}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserNotificationSettingResponse>> upsertSetting(
            @Parameter(description = "Event type identifier") @PathVariable String eventType,
            @RequestBody UpsertNotificationSettingRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Upsert notification setting userId={} eventType={}", userDetails.getUserId(), eventType);
        boolean inApp = request.getInAppEnabled() != null ? request.getInAppEnabled() : true;
        boolean push = request.getPushEnabled() != null ? request.getPushEnabled() : true;
        UserNotificationSettingResponse result = settingService.upsertSetting(
                userDetails.getUserId(), eventType, inApp, push);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
            summary = "Bulk upsert notification settings",
            description = "Creates or updates multiple notification settings for the authenticated user "
                    + "in a single request. Each entry must include an eventType. "
                    + "Missing boolean fields default to true.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Settings created or updated",
                content = @Content(schema = @Schema(implementation = UserNotificationSettingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation error — settings list must not be empty"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Unauthorized — valid JWT required")
    })
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<UserNotificationSettingResponse>>> bulkUpsertSettings(
            @Valid @RequestBody BulkUpsertSettingsRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Bulk upsert {} notification settings userId={}",
                request.getSettings().size(), userDetails.getUserId());
        List<UserNotificationSettingResponse> result = settingService.upsertSettings(
                userDetails.getUserId(), request.getSettings());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
