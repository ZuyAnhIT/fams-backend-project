package com.fams.modules.notification.controller;

import com.fams.modules.notification.dto.request.CreateNotificationRequest;
import com.fams.modules.notification.dto.response.MarkAllReadResponse;
import com.fams.modules.notification.dto.response.NotificationPageResponse;
import com.fams.modules.notification.dto.response.NotificationResponse;
import com.fams.modules.notification.service.NotificationService;
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

import java.util.UUID;

@Slf4j
@Tag(name = "Notifications", description = "In-app notification inbox")
@RestController
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Operation(
      summary = "Get notification inbox",
      description =
          "Returns a paginated list of notifications for the authenticated user in the specified "
              + "tenant, sorted newest first. The response also includes the total unread count "
              + "for this user regardless of any filters applied.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Paginated notification list returned",
        content =
            @Content(schema = @Schema(implementation = NotificationPageResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @GetMapping("/api/v1/tenants/{tenantId}/notifications")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<NotificationPageResponse>> getNotifications(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Zero-based page index (default 0)")
          @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (default 20, max 100)")
          @RequestParam(defaultValue = "20")
          int size,
      @Parameter(description = "When true, returns only unread notifications")
          @RequestParam(defaultValue = "false")
          boolean unreadOnly,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    size = Math.min(size, 100);
    log.info(
        "Get notifications tenantId={} userId={} page={} size={} unreadOnly={}",
        tenantId,
        userDetails.getUserId(),
        page,
        size,
        unreadOnly);
    NotificationPageResponse result =
        notificationService.getNotifications(
            tenantId, userDetails.getUserId(), page, size, unreadOnly);
    return ResponseEntity.ok(ApiResponse.success(result));
  }

  @Operation(
      summary = "Mark a notification as read",
      description =
          "Marks a single notification as read. The notification must belong to the authenticated "
              + "user in the specified tenant. Returns 404 if not found or not owned by the user "
              + "(no 403 to avoid user enumeration).")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Notification marked as read",
        content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Notification not found or does not belong to this user")
  })
  @PatchMapping("/api/v1/tenants/{tenantId}/notifications/{notificationId}/read")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Notification UUID") @PathVariable UUID notificationId,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    log.info(
        "Mark as read notificationId={} tenantId={} userId={}",
        notificationId,
        tenantId,
        userDetails.getUserId());
    NotificationResponse response =
        notificationService.markAsRead(notificationId, tenantId, userDetails.getUserId());
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @Operation(
      summary = "Mark all notifications as read",
      description =
          "Marks all unread notifications as read for the authenticated user in the specified "
              + "tenant. Returns the count of notifications that were updated.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "All notifications marked as read",
        content = @Content(schema = @Schema(implementation = MarkAllReadResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @PatchMapping("/api/v1/tenants/{tenantId}/notifications/read-all")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<MarkAllReadResponse>> markAllAsRead(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    log.info(
        "Mark all as read tenantId={} userId={}", tenantId, userDetails.getUserId());
    int count = notificationService.markAllAsRead(tenantId, userDetails.getUserId());
    return ResponseEntity.ok(ApiResponse.success(new MarkAllReadResponse(count)));
  }

  @Operation(
      summary = "Create notification (internal)",
      description =
          "Internal endpoint for creating notifications. Used by other services or jobs. "
              + "No authentication required — restrict at network level in production.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Notification created",
        content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error")
  })
  @PostMapping("/internal/notifications")
  public ResponseEntity<ApiResponse<NotificationResponse>> createNotification(
      @Valid @RequestBody CreateNotificationRequest request) {
    log.info(
        "Internal create notification tenantId={} userId={} eventType={}",
        request.getTenantId(),
        request.getUserId(),
        request.getEventType());
    NotificationResponse response = notificationService.createNotification(request);
    return ResponseEntity.status(201).body(ApiResponse.success(response));
  }
}
