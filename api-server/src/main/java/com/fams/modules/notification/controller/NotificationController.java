package com.fams.modules.notification.controller;

import com.fams.modules.notification.constant.NotificationEventTypeCatalog;
import com.fams.modules.notification.dto.request.CreateNotificationRequest;
import com.fams.modules.notification.dto.request.MarkReadBatchRequest;
import com.fams.modules.notification.dto.response.MarkAllReadResponse;
import com.fams.modules.notification.dto.response.NotificationEventTypeResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "Notifications", description = "In-app notification inbox")
@RestController
public class NotificationController {

  private final NotificationService notificationService;
  private final String internalSecret;

  public NotificationController(NotificationService notificationService,
                                 @Value("${app.notifications.internal-secret}") String internalSecret) {
    this.notificationService = notificationService;
    this.internalSecret = internalSecret;
  }

  @Operation(
      summary = "Get the official catalog of notification event types",
      description =
          "Returns every event type the system sends notifications for, with a human-readable "
              + "label and the system default in-app/push flags — the single source of truth so "
              + "FE never has to hardcode or guess event type strings (added 2026-08-05). Not "
              + "tenant-scoped (the catalog is system-wide, not customized per tenant). Useful both "
              + "for the personal notification-settings screen (see GET /me/notification-settings, "
              + "which now already merges this same catalog) and for an HR-facing screen managing "
              + "notification templates (POST .../tenants/{tenantId}/notification-templates), where "
              + "eventType is otherwise free text.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Event type catalog returned",
        content = @Content(schema = @Schema(implementation = NotificationEventTypeResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @GetMapping("/api/v1/notification-event-types")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<List<NotificationEventTypeResponse>>> getEventTypeCatalog() {
    List<NotificationEventTypeResponse> catalog = NotificationEventTypeCatalog.ALL.stream()
        .map(info -> NotificationEventTypeResponse.builder()
            .eventType(info.eventType())
            .label(info.label())
            .description(info.description())
            .defaultInAppEnabled(info.defaultInAppEnabled())
            .defaultPushEnabled(info.defaultPushEnabled())
            .build())
        .collect(Collectors.toList());
    return ResponseEntity.ok(ApiResponse.success(catalog));
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
      summary = "Mark a specific set of notifications as read",
      description =
          "Marks the given notification IDs as read for the authenticated user in the specified "
              + "tenant — the multi-select case between marking exactly one and marking the whole "
              + "inbox (added 2026-08-05). IDs not belonging to this user/tenant, already read, or "
              + "not found are silently skipped rather than erroring. Returns the count actually updated.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Notifications marked as read",
        content = @Content(schema = @Schema(implementation = MarkAllReadResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error — notificationIds must not be empty"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @PatchMapping("/api/v1/tenants/{tenantId}/notifications/read")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<MarkAllReadResponse>> markBatchAsRead(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Valid @RequestBody MarkReadBatchRequest request,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    log.info("Mark batch as read tenantId={} userId={} count={}",
        tenantId, userDetails.getUserId(), request.getNotificationIds().size());
    int count = notificationService.markAsReadBatch(
        tenantId, userDetails.getUserId(), request.getNotificationIds());
    return ResponseEntity.ok(ApiResponse.success(new MarkAllReadResponse(count)));
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
          "Internal endpoint for creating notifications, for a genuinely external caller "
              + "(in-process code creates notifications via NotificationService directly, never "
              + "through this HTTP path). Requires the X-Internal-Secret header (audit 2026-08-05 "
              + "— previously had no authentication at all, same protection now applied as the "
              + "AI face-result callback).")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Notification created",
        content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Missing or invalid X-Internal-Secret header")
  })
  @PostMapping("/internal/notifications")
  public ResponseEntity<ApiResponse<NotificationResponse>> createNotification(
      @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
      @Valid @RequestBody CreateNotificationRequest request) {
    if (!internalSecret.equals(secret)) {
      log.warn("Internal notification create rejected: invalid X-Internal-Secret");
      return ResponseEntity.status(403).body(ApiResponse.error("Forbidden"));
    }
    log.info(
        "Internal create notification tenantId={} userId={} eventType={}",
        request.getTenantId(),
        request.getUserId(),
        request.getEventType());
    NotificationResponse response = notificationService.createNotification(request);
    return ResponseEntity.status(201).body(ApiResponse.success(response));
  }
}
