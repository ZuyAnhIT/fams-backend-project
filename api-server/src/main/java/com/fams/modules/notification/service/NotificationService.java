package com.fams.modules.notification.service;

import com.fams.modules.notification.dto.request.CreateNotificationRequest;
import com.fams.modules.notification.dto.response.NotificationPageResponse;
import com.fams.modules.notification.dto.response.NotificationResponse;
import com.fams.modules.notification.entity.Notification;
import com.fams.modules.notification.repository.NotificationRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final UserNotificationSettingService userNotificationSettingService;

  public NotificationService(
      NotificationRepository notificationRepository,
      @Lazy UserNotificationSettingService userNotificationSettingService) {
    this.notificationRepository = notificationRepository;
    this.userNotificationSettingService = userNotificationSettingService;
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
    log.info(
        "Creating notification tenantId={} userId={} eventType={}", tenantId, userId, eventType);

    if (!userNotificationSettingService.isInAppEnabled(userId, eventType)) {
      log.info(
          "In-app notifications disabled for userId={} eventType={} — skipping insert",
          userId,
          eventType);
      return null;
    }

    Notification notification =
        Notification.builder()
            .tenantId(tenantId)
            .userId(userId)
            .eventType(eventType)
            .title(title)
            .body(body)
            .isRead(false)
            .build();

    Notification saved = notificationRepository.save(notification);
    log.debug("Notification created id={}", saved.getId());
    return toResponse(saved);
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
        .isRead(n.isRead())
        .readAt(n.getReadAt())
        .createdAt(n.getCreatedAt())
        .build();
  }
}
