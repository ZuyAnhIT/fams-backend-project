package com.fams.shared.client;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FcmClient {

  private static final int MAX_ATTEMPTS = 3;
  private static final long BASE_DELAY_MS = 1_000L;

  public record SendResult(boolean success, String messageId, int attempts, String lastError) {}

  /**
   * Sends a push notification with exponential backoff retry (max 3 attempts).
   * Delays: 1 s after attempt 1, 2 s after attempt 2.
   *
   * @return SendResult with success flag, FCM messageId (on success), attempt count, last error
   */
  public SendResult sendToToken(String deviceToken, String title, String body) {
    if (FirebaseApp.getApps().isEmpty()) {
      log.debug("FCM not initialized — skipping push to token ending in ...{}",
          deviceToken.length() > 8 ? deviceToken.substring(deviceToken.length() - 8) : deviceToken);
      return new SendResult(false, null, 0, "FCM_NOT_INITIALIZED");
    }

    Message message = Message.builder()
        .setToken(deviceToken)
        .setNotification(Notification.builder()
            .setTitle(title)
            .setBody(body)
            .build())
        .build();

    String lastError = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        String messageId = FirebaseMessaging.getInstance().send(message);
        log.debug("FCM push sent messageId={} token=...{} attempt={}", messageId,
            deviceToken.length() > 8 ? deviceToken.substring(deviceToken.length() - 8) : deviceToken,
            attempt);
        return new SendResult(true, messageId, attempt, null);
      } catch (FirebaseMessagingException e) {
        lastError = e.getMessagingErrorCode() != null
            ? e.getMessagingErrorCode().name() + ": " + e.getMessage()
            : e.getMessage();
        log.warn("FCM attempt {}/{} failed for token=...{}: {}",
            attempt, MAX_ATTEMPTS,
            deviceToken.length() > 8 ? deviceToken.substring(deviceToken.length() - 8) : deviceToken,
            lastError);

        if (attempt < MAX_ATTEMPTS) {
          long delay = BASE_DELAY_MS * (1L << (attempt - 1)); // 1s, 2s
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new SendResult(false, null, attempt, "INTERRUPTED");
          }
        }
      }
    }

    log.error("FCM push permanently failed after {} attempts for token=...{}", MAX_ATTEMPTS,
        deviceToken.length() > 8 ? deviceToken.substring(deviceToken.length() - 8) : deviceToken);
    return new SendResult(false, null, MAX_ATTEMPTS, lastError);
  }
}
