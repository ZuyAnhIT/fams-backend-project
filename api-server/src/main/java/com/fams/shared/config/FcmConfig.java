package com.fams.shared.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class FcmConfig {

  @Value("${app.fcm.project-id:}")
  private String projectId;

  @Value("${app.fcm.service-account-json:}")
  private String serviceAccountJson;

  @PostConstruct
  public void initialize() {
    if (projectId == null || projectId.isBlank()
        || serviceAccountJson == null || serviceAccountJson.isBlank()) {
      log.warn("FCM not configured (FCM_PROJECT_ID or FCM_SERVICE_ACCOUNT_JSON missing). Push notifications disabled.");
      return;
    }

    if (!FirebaseApp.getApps().isEmpty()) {
      log.info("FirebaseApp already initialized — skipping.");
      return;
    }

    try {
      InputStream stream = new ByteArrayInputStream(
          serviceAccountJson.getBytes(StandardCharsets.UTF_8));
      FirebaseOptions options = FirebaseOptions.builder()
          .setCredentials(GoogleCredentials.fromStream(stream))
          .setProjectId(projectId)
          .build();
      FirebaseApp.initializeApp(options);
      log.info("Firebase Admin SDK initialized for project '{}'", projectId);
    } catch (Exception e) {
      log.error("Failed to initialize Firebase Admin SDK: {}", e.getMessage(), e);
    }
  }
}
