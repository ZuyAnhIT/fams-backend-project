package com.fams.modules.notification.job;

import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.notification.repository.NotificationDeliveryLogRepository;
import com.fams.modules.notification.repository.NotificationRepository;
import com.fams.shared.ai.AiServiceClient;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
public class DataRetentionJob {

    private static final String JOB_NAME = "DataRetentionJob";

    private final NotificationDeliveryLogRepository deliveryLogRepository;
    private final NotificationRepository notificationRepository;
    private final FaceProfileRepository faceProfileRepository;
    private final AiServiceClient aiServiceClient;
    private final ScheduledJobMonitor jobMonitor;

    @Value("${app.data-retention.delivery-log-days:30}")
    private int deliveryLogDays;

    @Value("${app.data-retention.notification-days:90}")
    private int notificationDays;

    // No pre-existing legal/consent-linked number was found anywhere in the codebase for
    // biometric photos specifically (checked FaceProfile entity, consent fields, docs) — 30 days
    // chosen as a reasonable default matching the existing delivery-log-days retention already
    // used in this same job, NOT a confirmed compliance/legal requirement. Flag to product/legal
    // before relying on this for real compliance — override via env var if a different number is
    // decided. See docs/api/random-check-config-review.md for the open item this closes.
    @Value("${app.data-retention.biometric-photo-days:30}")
    private int biometricPhotoDays;

    public DataRetentionJob(
            NotificationDeliveryLogRepository deliveryLogRepository,
            NotificationRepository notificationRepository,
            FaceProfileRepository faceProfileRepository,
            AiServiceClient aiServiceClient,
            ScheduledJobMonitor jobMonitor) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.notificationRepository = notificationRepository;
        this.faceProfileRepository = faceProfileRepository;
        this.aiServiceClient = aiServiceClient;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(cron = "0 0 3 * * SUN")
    @Transactional
    public void runRetention() {
        log.info("DataRetentionJob starting");
        try {
            purgeDeliveryLogs();
            purgeReadNotifications();
            purgeRevokedFaceEmbeddings();
            purgeOldBiometricPhotos();
            jobMonitor.recordSuccess(JOB_NAME);
            log.info("DataRetentionJob completed");
        } catch (Exception e) {
            log.error("DataRetentionJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, e);
        }
    }

    private void purgeDeliveryLogs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deliveryLogDays);
        int deleted = deliveryLogRepository.deleteByCreatedAtBefore(cutoff);
        log.info("DataRetentionJob — deleted {} delivery log(s) older than {} days", deleted, deliveryLogDays);
    }

    private void purgeReadNotifications() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(notificationDays);
        int deleted = notificationRepository.deleteReadNotificationsOlderThan(cutoff);
        log.info("DataRetentionJob — deleted {} read notification(s) older than {} days", deleted, notificationDays);
    }

    private void purgeRevokedFaceEmbeddings() {
        List<FaceProfile> profiles = faceProfileRepository.findAllByRevokedAtIsNotNullAndEmbeddingDeletedFalse();
        int success = 0;
        for (FaceProfile profile : profiles) {
            try {
                aiServiceClient.deleteEmbedding(profile.getId());
                profile.setEmbeddingDeleted(true);
                faceProfileRepository.save(profile);
                success++;
            } catch (Exception e) {
                log.error("DataRetentionJob — failed to delete embedding faceProfileId={}: {}",
                        profile.getId(), e.getMessage());
            }
        }
        log.info("DataRetentionJob — deleted {}/{} face embedding(s)", success, profiles.size());
    }

    /** Checkin/random-check selfies + liveness challenge frames only — deliberately excludes
     *  enrollment photos, which need DB-aware handling (skip anything still referenced by a
     *  pending review) rather than a pure age sweep. See ai-service's POST /checkins/cleanup. */
    private void purgeOldBiometricPhotos() {
        try {
            var result = aiServiceClient.cleanupOldCheckinPhotos(biometricPhotoDays);
            log.info("DataRetentionJob — biometric photo sweep (older than {} days): {}",
                    biometricPhotoDays, result);
        } catch (Exception e) {
            log.error("DataRetentionJob — biometric photo sweep failed: {}", e.getMessage());
        }
    }
}
