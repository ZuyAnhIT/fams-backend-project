package com.fams.modules.notification.job;

import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.notification.repository.NotificationDeliveryLogRepository;
import com.fams.modules.notification.repository.NotificationRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.entity.TenantSettings;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.repository.TenantSettingsRepository;
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
    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;

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
            ScheduledJobMonitor jobMonitor,
            TenantRepository tenantRepository,
            TenantSettingsRepository tenantSettingsRepository) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.notificationRepository = notificationRepository;
        this.faceProfileRepository = faceProfileRepository;
        this.aiServiceClient = aiServiceClient;
        this.jobMonitor = jobMonitor;
        this.tenantRepository = tenantRepository;
        this.tenantSettingsRepository = tenantSettingsRepository;
    }

    @Scheduled(cron = "0 0 3 * * SUN")
    @Transactional
    public void runRetention() {
        long startedAt = System.currentTimeMillis();
        log.info("DataRetentionJob starting");
        try {
            purgeDeliveryLogs();
            purgePerTenant();
            purgeRevokedFaceEmbeddings();
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
            log.info("DataRetentionJob completed");
        } catch (Exception e) {
            log.error("DataRetentionJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }

    /** #144 (2026-08-19 follow-up): migration V109 added tenant_id to NotificationDeliveryLog,
     *  so tenant-scoped rows are now swept per-tenant inside {@link #purgePerTenant()} using each
     *  tenant's own effective retention window. This global pass only remains for rows where
     *  tenant_id is genuinely NULL (platform-admin ops alerts with no tenant context, or a row
     *  whose notificationId link predates the backfill) — those can never be reached by the
     *  per-tenant query, not a design choice to skip per-tenant handling. */
    private void purgeDeliveryLogs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deliveryLogDays);
        int deleted = deliveryLogRepository.deleteByCreatedAtBefore(cutoff);
        log.info("DataRetentionJob — deleted {} tenant-less delivery log(s) older than {} days (global default)",
                deleted, deliveryLogDays);
    }

    /** #144 (2026-08-19): loops every active tenant, resolving each one's effective retention
     *  window (tenant_settings.data_retention_days override, falling back to the platform
     *  defaults) — previously this whole job applied one hardcoded global cutoff to every
     *  tenant's notifications and biometric photos alike, ignoring the per-tenant column added
     *  by this same fix. Read notifications and checkin/liveness-challenge photos ARE tenant-
     *  scoped in storage (see AiServiceClient#cleanupOldCheckinPhotos javadoc), so both can
     *  honor a tenant-specific override; delivery logs cannot (see purgeDeliveryLogs). */
    private void purgePerTenant() {
        List<Tenant> tenants = tenantRepository.findAllByDeletedAtIsNull();
        int totalNotificationsDeleted = 0;
        int tenantsWithOverride = 0;
        for (Tenant tenant : tenants) {
            Integer override = tenantSettingsRepository.findByTenantId(tenant.getId())
                    .map(TenantSettings::getDataRetentionDays)
                    .orElse(null);
            if (override != null) {
                tenantsWithOverride++;
            }
            int effectiveNotificationDays = override != null ? override : notificationDays;
            int effectivePhotoDays = override != null ? override : biometricPhotoDays;
            int effectiveDeliveryLogDays = override != null ? override : deliveryLogDays;

            OffsetDateTime notificationCutoff = OffsetDateTime.now().minusDays(effectiveNotificationDays);
            totalNotificationsDeleted += notificationRepository
                    .deleteReadNotificationsOlderThan(tenant.getId(), notificationCutoff);

            deliveryLogRepository.deleteByTenantIdAndCreatedAtBefore(
                    tenant.getId(), OffsetDateTime.now().minusDays(effectiveDeliveryLogDays));

            try {
                var result = aiServiceClient.cleanupOldCheckinPhotos(effectivePhotoDays, tenant.getId());
                log.debug("DataRetentionJob — biometric photo sweep tenantId={} olderThanDays={}: {}",
                        tenant.getId(), effectivePhotoDays, result);
            } catch (Exception e) {
                log.error("DataRetentionJob — biometric photo sweep failed tenantId={}: {}",
                        tenant.getId(), e.getMessage());
            }
        }
        log.info("DataRetentionJob — swept {} tenant(s) ({} with a data_retention_days override), "
                        + "deleted {} read notification(s) total",
                tenants.size(), tenantsWithOverride, totalNotificationsDeleted);
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
}
