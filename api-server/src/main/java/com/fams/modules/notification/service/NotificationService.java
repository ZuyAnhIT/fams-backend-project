package com.fams.modules.notification.service;

import com.fams.modules.notification.dto.request.CreateNotificationRequest;
import com.fams.modules.notification.dto.response.NotificationPageResponse;
import com.fams.modules.notification.dto.response.NotificationResponse;
import com.fams.modules.notification.entity.Notification;
import com.fams.modules.notification.repository.NotificationRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final UserNotificationSettingService userNotificationSettingService;
  private final UserDeviceService userDeviceService;
  private final NotificationTemplateService notificationTemplateService;
  private final TenantRepository tenantRepository;

  public NotificationService(
      NotificationRepository notificationRepository,
      @Lazy UserNotificationSettingService userNotificationSettingService,
      @Lazy UserDeviceService userDeviceService,
      NotificationTemplateService notificationTemplateService,
      TenantRepository tenantRepository) {
    this.notificationRepository = notificationRepository;
    this.userNotificationSettingService = userNotificationSettingService;
    this.userDeviceService = userDeviceService;
    this.notificationTemplateService = notificationTemplateService;
    this.tenantRepository = tenantRepository;
  }

  /**
   * Returns a paginated inbox for the authenticated user in a tenant.
   *
   * @param tenantId tenant UUID from path
   * @param userId user UUID from JWT
   * @param page zero-based page index
   * @param size page size
   * @param unreadOnly when true, returns only unread notifications
   * @return paginated response including total unread count
   */
  @Transactional(readOnly = true)
  public NotificationPageResponse getNotifications(
      UUID tenantId, UUID userId, int page, int size, boolean unreadOnly) {
    log.debug(
        "Fetching notifications tenantId={} userId={} page={} size={} unreadOnly={}",
        tenantId,
        userId,
        page,
        size,
        unreadOnly);

    PageRequest pageable = PageRequest.of(page, size);

    Page<Notification> pageResult;
    if (unreadOnly) {
      pageResult = notificationRepository.findUnreadByTenantAndUser(tenantId, userId, pageable);
    } else {
      pageResult = notificationRepository.findByTenantAndUser(tenantId, userId, pageable);
    }

    long unreadCount = notificationRepository.countUnreadByTenantAndUser(tenantId, userId);

    List<NotificationResponse> items =
        pageResult.getContent().stream().map(this::toResponse).collect(Collectors.toList());

    return NotificationPageResponse.builder()
        .items(items)
        .page(pageResult.getNumber())
        .size(pageResult.getSize())
        .totalElements(pageResult.getTotalElements())
        .totalPages(pageResult.getTotalPages())
        .first(pageResult.isFirst())
        .last(pageResult.isLast())
        .unreadCount(unreadCount)
        .build();
  }

  /**
   * Creates a notification. Can be called from other services or the internal REST endpoint.
   *
   * @param tenantId tenant UUID
   * @param userId target user UUID
   * @param eventType event type string
   * @param title notification title
   * @param body notification body (may be null)
   * @return the created notification as a response DTO
   */
  @Transactional
  public NotificationResponse createNotification(
      UUID tenantId, UUID userId, String eventType, String title, String body) {
    return createNotification(tenantId, userId, eventType, title, body, null);
  }

  /**
   * Same as the 5-arg overload, plus an optional structured payload for deep-linking (e.g.
   * {"checkId": ..., "siteId": ..., "expiresAt": ...} for RANDOM_CHECK_SENT) — found missing via
   * FE audit (2026-07-31): callers had no way to attach machine-readable data alongside the
   * human-readable title/body, forcing clients to fall back to opening a generic list screen.
   * As of 2026-08-01 this is also forwarded into the FCM push data payload (see
   * FcmClient.sendToToken's data-map overload) — reachable even while the app is fully closed,
   * not just once it opens and syncs GET /notifications.
   *
   * In-app and push are independently gated — found via FE audit (2026-08-01): this method
   * previously `return`ed as soon as in-app was disabled, before push was ever checked, so
   * disabling in-app notifications for an eventType silently ALSO killed push for that eventType
   * even if the user's push preference was separately ON. Matches the schema, which already has
   * two independent booleans (in_app_enabled, push_enabled) per user+eventType — the bug was
   * purely in this method's control flow, not a schema limitation.
   *
   * @param metadata structured payload, or null for notification types that don't need one
   */
  @Transactional
  public NotificationResponse createNotification(
      UUID tenantId, UUID userId, String eventType, String title, String body,
      Map<String, Object> metadata) {
    log.info(
        "Creating notification tenantId={} userId={} eventType={}", tenantId, userId, eventType);

    // Admin-configured template (audit 2026-08-06) overrides the caller-supplied title/body when
    // one exists for this tenant+eventType+locale — this is the ONLY place in the codebase that
    // ever consulted NotificationTemplateService before this fix, so it's the single choke point
    // every notification (present and future) goes through, guaranteeing an admin-edited template
    // actually changes what gets sent. Falls back to the caller's hardcoded defaults when no
    // template is configured (no seed data required, no risk of breaking a send just because a
    // tenant never bothered to customize this eventType). Uses the non-throwing
    // renderTemplateIfExists, NOT renderTemplate — see that method's Javadoc for why the throwing
    // version silently breaks every send in this exact call position (nested @Transactional
    // exception marks the shared transaction rollback-only even when caught locally).
    String resolvedTitle = title;
    String resolvedBody = body;
    String locale = resolveLocale(tenantId);
    Map<String, String> vars = toPushDataPayload(eventType, metadata);
    java.util.Optional<String[]> rendered =
        notificationTemplateService.renderTemplateIfExists(tenantId, eventType, locale, vars);
    if (rendered.isPresent()) {
      resolvedTitle = rendered.get()[0];
      resolvedBody = rendered.get()[1];
    } else {
      log.debug("No custom template for tenantId={} eventType={} — using caller-supplied text",
              tenantId, eventType);
    }

    boolean inAppEnabled = userNotificationSettingService.isInAppEnabled(userId, eventType);
    boolean pushEnabled = userNotificationSettingService.isPushEnabled(userId, eventType);

    Notification saved = null;
    if (inAppEnabled) {
      Notification notification =
          Notification.builder()
              .tenantId(tenantId)
              .userId(userId)
              .eventType(eventType)
              .title(resolvedTitle)
              .body(resolvedBody)
              .metadata(metadata)
              .priority(com.fams.modules.notification.constant.NotificationEventTypeCatalog
                      .defaultPriorityFor(eventType))
              .isRead(false)
              .build();
      saved = notificationRepository.save(notification);
      log.debug("Notification created id={}", saved.getId());
    } else {
      log.info("In-app notifications disabled for userId={} eventType={} — skipping insert "
              + "(push evaluated independently, see below)", userId, eventType);
    }

    if (pushEnabled) {
      Map<String, String> pushData = toPushDataPayload(eventType, metadata);
      // notificationId may be null here (in-app disabled, push-only path) — the delivery log
      // FK already tolerates that, see UserDeviceService.sendPush's Javadoc.
      // #140 (2026-08-19): email fallback is scoped to critical priority only — AC requires it,
      // previously every eventType fell back regardless.
      boolean fallbackEligible = "critical".equals(
              com.fams.modules.notification.constant.NotificationEventTypeCatalog.defaultPriorityFor(eventType));
      userDeviceService.sendPush(saved != null ? saved.getId() : null, tenantId, userId, resolvedTitle, resolvedBody,
              pushData, fallbackEligible);
    }

    return saved != null ? toResponse(saved) : null;
  }

  /** Tenant's configured locale, defaulting to "vi" — matches NotificationTemplate's own default
   *  (see NotificationTemplateService.createTemplate) since there is no per-user locale field. */
  private String resolveLocale(UUID tenantId) {
    return tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
            .map(t -> t.getLocale() != null ? t.getLocale() : "vi")
            .orElse("vi");
  }

  /** FCM data payloads are always String→String — stringify eventType + every metadata value,
   *  dropping null entries (FCM rejects null data values). eventType is always included even if
   *  the caller's metadata map didn't carry it, so the app never has to guess which event fired. */
  private Map<String, String> toPushDataPayload(String eventType, Map<String, Object> metadata) {
    Map<String, String> data = new java.util.LinkedHashMap<>();
    data.put("eventType", eventType);
    if (metadata != null) {
      metadata.forEach((k, v) -> {
        if (v != null) data.put(k, String.valueOf(v));
      });
    }
    return data;
  }

  /**
   * Creates a notification from a {@link CreateNotificationRequest}.
   *
   * @param request the creation request DTO
   * @return the created notification as a response DTO
   */
  @Transactional
  public NotificationResponse createNotification(CreateNotificationRequest request) {
    return createNotification(
        request.getTenantId(),
        request.getUserId(),
        request.getEventType(),
        request.getTitle(),
        request.getBody());
  }

  /**
   * Marks a single notification as read. The notification must belong to the given user and tenant.
   *
   * @param notificationId notification UUID
   * @param tenantId tenant UUID from path
   * @param userId user UUID from JWT
   * @return the updated notification as a response DTO
   * @throws ResourceNotFoundException if the notification is not found or does not belong to user
   */
  @Transactional
  public NotificationResponse markAsRead(UUID notificationId, UUID tenantId, UUID userId) {
    log.info(
        "Mark as read notificationId={} tenantId={} userId={}", notificationId, tenantId, userId);

    Notification notification =
        notificationRepository
            .findByIdAndTenantIdAndUserId(notificationId, tenantId, userId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Notification not found: " + notificationId));

    if (!notification.isRead()) {
      notification.setRead(true);
      notification.setReadAt(OffsetDateTime.now());
      notification = notificationRepository.save(notification);
      log.debug("Notification marked as read id={}", notificationId);
    } else {
      log.debug("Notification already read id={}", notificationId);
    }

    return toResponse(notification);
  }

  /**
   * Marks a specific set of notifications as read — the "select several from the inbox" case
   * between markAsRead (exactly one) and markAllAsRead (everything). IDs that don't exist, don't
   * belong to this user/tenant, or are already read are silently skipped rather than erroring —
   * matches markAllAsRead's own idempotent style.
   *
   * @return the number of notifications actually updated
   */
  @Transactional
  public int markAsReadBatch(UUID tenantId, UUID userId, List<UUID> notificationIds) {
    log.info("Mark batch as read tenantId={} userId={} count={}", tenantId, userId, notificationIds.size());
    if (notificationIds.isEmpty()) {
      return 0;
    }
    int count = notificationRepository.markAsReadBatch(tenantId, userId, notificationIds);
    log.debug("Marked {} notifications as read for userId={}", count, userId);
    return count;
  }

  /**
   * Marks all unread notifications as read for the given user in a tenant.
   *
   * @param tenantId tenant UUID from path
   * @param userId user UUID from JWT
   * @return the number of notifications marked as read
   */
  @Transactional
  public int markAllAsRead(UUID tenantId, UUID userId) {
    log.info("Mark all as read tenantId={} userId={}", tenantId, userId);
    int count = notificationRepository.markAllAsRead(tenantId, userId);
    log.debug("Marked {} notifications as read for userId={}", count, userId);
    return count;
  }

  private NotificationResponse toResponse(Notification n) {
    return NotificationResponse.builder()
        .id(n.getId())
        .tenantId(n.getTenantId())
        .userId(n.getUserId())
        .eventType(n.getEventType())
        .title(n.getTitle())
        .body(n.getBody())
        .metadata(n.getMetadata())
        .priority(n.getPriority())
        .isRead(n.isRead())
        .readAt(n.getReadAt())
        .createdAt(n.getCreatedAt())
        .build();
  }
}
