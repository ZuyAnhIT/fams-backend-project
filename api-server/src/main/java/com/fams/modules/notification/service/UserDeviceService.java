package com.fams.modules.notification.service;

import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.service.EmailService;
import com.fams.modules.notification.entity.NotificationDeliveryLog;
import com.fams.modules.notification.entity.UserDevice;
import com.fams.modules.notification.repository.NotificationDeliveryLogRepository;
import com.fams.modules.notification.repository.UserDeviceRepository;
import com.fams.shared.client.FcmClient;
import com.fams.shared.client.FcmClient.SendResult;
import com.fams.shared.util.MaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class UserDeviceService {

  private final UserDeviceRepository userDeviceRepository;
  private final NotificationDeliveryLogRepository deliveryLogRepository;
  private final FcmClient fcmClient;
  private final UserRepository userRepository;
  private final EmailService emailService;

  public UserDeviceService(
      UserDeviceRepository userDeviceRepository,
      NotificationDeliveryLogRepository deliveryLogRepository,
      FcmClient fcmClient,
      UserRepository userRepository,
      EmailService emailService) {
    this.userDeviceRepository = userDeviceRepository;
    this.deliveryLogRepository = deliveryLogRepository;
    this.fcmClient = fcmClient;
    this.userRepository = userRepository;
    this.emailService = emailService;
  }

  @Transactional
  public UserDevice registerDevice(UUID userId, String deviceToken, String platform) {
    Optional<UserDevice> existing = userDeviceRepository.findActiveByToken(deviceToken);
    if (existing.isPresent()) {
      UserDevice device = existing.get();
      if (!device.getUserId().equals(userId)) {
        device.setUserId(userId);
        log.info("Device token reassigned to userId={}", userId);
      } else {
        log.debug("Device token already registered for userId={}", userId);
      }
      return userDeviceRepository.save(device);
    }

    UserDevice device = UserDevice.builder()
        .userId(userId)
        .deviceToken(deviceToken)
        .platform(platform != null ? platform : "FCM")
        .build();
    UserDevice saved = userDeviceRepository.save(device);
    log.info("Device registered id={} userId={}", saved.getId(), userId);
    return saved;
  }

  @Transactional
  public void unregisterDevice(UUID userId, String deviceToken) {
    userDeviceRepository.findActiveByToken(deviceToken).ifPresent(device -> {
      if (device.getUserId().equals(userId)) {
        device.setDeletedAt(OffsetDateTime.now());
        userDeviceRepository.save(device);
        log.info("Device unregistered token={} userId={}", MaskingUtils.maskToken(deviceToken), userId);
      }
    });
  }

  /**
   * Sends a push notification to all active devices of a user with retry + delivery logging.
   * Falls back to email if all FCM attempts fail for every device. No eventType here, so
   * fallback always fires regardless of priority — matches the only caller of this overload
   * (ScheduledJobMonitor's platform-admin job-failure alert), which is inherently ops-critical.
   *
   * @param notificationId in-app notification UUID (may be null for push-only paths)
   * @return number of devices that received a successful push
   */
  public int sendPush(UUID notificationId, UUID userId, String title, String body) {
    return sendPush(notificationId, userId, title, body, null, true);
  }

  /**
   * Same as the 4-arg overload, plus an optional FCM data payload — see FcmClient.sendToToken's
   * data-map overload. Needed so an event like RANDOM_CHECK_SENT can carry checkId/siteId/
   * expiresAt straight to the device even while the app is fully closed, instead of only being
   * retrievable once the app opens and syncs GET /notifications (which already carries this same
   * data in Notification.metadata, but only reachable from inside a running app).
   *
   * @deprecated fallback-eligibility must be explicit — use the 6-arg overload with
   *     {@code fallbackEligible} so a caller can't accidentally get (or accidentally not get)
   *     an email fallback for a non-critical eventType. Kept only for the ScheduledJobMonitor
   *     3-arg overload's internal delegation.
   */
  public int sendPush(UUID notificationId, UUID userId, String title, String body, Map<String, String> data) {
    return sendPush(notificationId, userId, title, body, data, true);
  }

  /**
   * #140 (2026-08-19): email fallback previously fired for EVERY notification whose push failed
   * on all devices, regardless of priority — Acceptance Criteria calls for fallback scoped to
   * {@code critical} priority only (non-critical notifications are fine to simply miss if push
   * fails; a low-priority "your report is ready" email is more annoying than useful). Callers
   * that know their eventType's priority pass it explicitly via {@code fallbackEligible} rather
   * than this method re-deriving it, since NotificationService already resolves priority once
   * via NotificationEventTypeCatalog and re-deriving here would duplicate that lookup.
   */
  public int sendPush(UUID notificationId, UUID userId, String title, String body, Map<String, String> data,
                       boolean fallbackEligible) {
    List<UserDevice> devices = userDeviceRepository.findActiveByUserId(userId);
    if (devices.isEmpty()) {
      log.debug("No active devices for userId={} — skipping FCM push", userId);
      return 0;
    }

    int sent = 0;
    for (UserDevice device : devices) {
      SendResult result = fcmClient.sendToToken(device.getDeviceToken(), title, body, data);

      String status = result.success() ? "SUCCESS" : "FAILED";
      deliveryLogRepository.save(NotificationDeliveryLog.builder()
          .notificationId(notificationId)
          .deviceToken(device.getDeviceToken())
          .channel("FCM")
          .attemptNumber(result.attempts())
          .status(status)
          .errorMessage(result.lastError())
          .providerMessageId(result.messageId())
          .build());

      if (result.success()) {
        sent++;
      } else if ("UNREGISTERED".equals(result.errorCode())) {
        // #88 (2026-08-17): token is permanently dead — deactivate now so every future push to
        // this user stops retrying it. Previously nothing ever cleared a dead token, so it was
        // retried 3x with backoff on EVERY send, forever, until someone deleted it by hand.
        device.setDeletedAt(OffsetDateTime.now());
        userDeviceRepository.save(device);
        log.info("Deactivated stale device id={} userId={} (FCM UNREGISTERED)", device.getId(), userId);
      }
    }

    log.info("FCM push: {}/{} devices succeeded for userId={}", sent, devices.size(), userId);

    if (sent == 0 && !devices.isEmpty() && fallbackEligible) {
      sendEmailFallback(notificationId, userId, title, body);
    }

    return sent;
  }

  /** Backward-compat overload without notificationId. */
  public int sendPush(UUID userId, String title, String body) {
    return sendPush(null, userId, title, body);
  }

  private void sendEmailFallback(UUID notificationId, UUID userId, String title, String body) {
    try {
      String email = userRepository.findByIdAndDeletedAtIsNull(userId)
          .map(u -> u.getEmail())
          .orElse(null);
      if (email == null) {
        log.warn("Email fallback skipped — no email found for userId={}", userId);
        return;
      }
      emailService.sendNotificationFallback(email, title, body);
      deliveryLogRepository.save(NotificationDeliveryLog.builder()
          .notificationId(notificationId)
          .deviceToken(null)
          .channel("EMAIL_FALLBACK")
          .attemptNumber(1)
          .status("FALLBACK_EMAIL_SENT")
          .errorMessage(null)
          .build());
      log.info("Email fallback sent to {} for userId={}", email, userId);
    } catch (Exception e) {
      log.error("Email fallback failed for userId={}: {}", userId, e.getMessage(), e);
      deliveryLogRepository.save(NotificationDeliveryLog.builder()
          .notificationId(notificationId)
          .deviceToken(null)
          .channel("EMAIL_FALLBACK")
          .attemptNumber(1)
          .status("FALLBACK_EMAIL_FAILED")
          .errorMessage(e.getMessage())
          .build());
    }
  }
}
