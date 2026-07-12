package com.fams.shared.monitoring;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.notification.service.UserDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class ScheduledJobMonitor {

    static final String REDIS_KEY_PREFIX = "job:status:";
    static final String FIELD_LAST_RUN_AT = "last_run_at";
    static final String FIELD_LAST_STATUS = "last_status";
    static final String FIELD_ERROR_MESSAGE = "error_message";
    static final String STATUS_OK = "OK";
    static final String STATUS_ERROR = "ERROR";

    private final StringRedisTemplate redisTemplate;
    private final ScheduledJobStatusRepository repository;
    private final UserRepository userRepository;
    private final UserDeviceService userDeviceService;

    public ScheduledJobMonitor(
            StringRedisTemplate redisTemplate,
            ScheduledJobStatusRepository repository,
            UserRepository userRepository,
            @Lazy UserDeviceService userDeviceService) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
        this.userRepository = userRepository;
        this.userDeviceService = userDeviceService;
    }

    public void recordSuccess(String jobName) {
        OffsetDateTime now = OffsetDateTime.now();
        writeToRedis(jobName, now, STATUS_OK, null);
        writeToDB(jobName, now, STATUS_OK, null);
        log.debug("Job '{}' completed successfully at {}", jobName, now);
    }

    public void recordFailure(String jobName, Throwable t) {
        OffsetDateTime now = OffsetDateTime.now();
        String errorMessage = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        writeToRedis(jobName, now, STATUS_ERROR, errorMessage);
        writeToDB(jobName, now, STATUS_ERROR, errorMessage);
        log.error("Job '{}' failed at {}: {}", jobName, now, errorMessage);
        notifyPlatformAdmins(jobName, errorMessage);
    }

    /**
     * Returns the latest status for a job — Redis first, DB as fallback.
     */
    public Optional<ScheduledJobStatus> getStatus(String jobName) {
        try {
            String lastRunAt = redisTemplate.opsForHash()
                    .get(REDIS_KEY_PREFIX + jobName, FIELD_LAST_RUN_AT) != null
                    ? (String) redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + jobName, FIELD_LAST_RUN_AT)
                    : null;
            if (lastRunAt != null) {
                String lastStatus = (String) redisTemplate.opsForHash()
                        .get(REDIS_KEY_PREFIX + jobName, FIELD_LAST_STATUS);
                String errorMessage = (String) redisTemplate.opsForHash()
                        .get(REDIS_KEY_PREFIX + jobName, FIELD_ERROR_MESSAGE);
                return Optional.of(ScheduledJobStatus.builder()
                        .jobName(jobName)
                        .lastRunAt(OffsetDateTime.parse(lastRunAt))
                        .lastStatus(lastStatus != null ? lastStatus : STATUS_OK)
                        .errorMessage(errorMessage)
                        .updatedAt(OffsetDateTime.parse(lastRunAt))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for job status '{}', falling back to DB: {}", jobName, e.getMessage());
        }
        return repository.findById(jobName);
    }

    private void writeToRedis(String jobName, OffsetDateTime runAt, String status, String errorMessage) {
        try {
            String key = REDIS_KEY_PREFIX + jobName;
            redisTemplate.opsForHash().put(key, FIELD_LAST_RUN_AT, runAt.toString());
            redisTemplate.opsForHash().put(key, FIELD_LAST_STATUS, status);
            redisTemplate.opsForHash().put(key, FIELD_ERROR_MESSAGE,
                    errorMessage != null ? errorMessage : "");
        } catch (Exception e) {
            log.warn("Failed to write job status to Redis for '{}': {}", jobName, e.getMessage());
        }
    }

    private void writeToDB(String jobName, OffsetDateTime runAt, String status, String errorMessage) {
        try {
            ScheduledJobStatus record = ScheduledJobStatus.builder()
                    .jobName(jobName)
                    .lastRunAt(runAt)
                    .lastStatus(status)
                    .errorMessage(errorMessage)
                    .updatedAt(runAt)
                    .build();
            repository.save(record);
        } catch (Exception e) {
            log.error("Failed to persist job status to DB for '{}': {}", jobName, e.getMessage());
        }
    }

    private void notifyPlatformAdmins(String jobName, String errorMessage) {
        try {
            List<User> admins = userRepository.findAllByIsPlatformAdminTrueAndDeletedAtIsNull();
            if (admins.isEmpty()) {
                log.warn("No platform admins found to notify about job failure: {}", jobName);
                return;
            }
            String title = "Scheduled Job Failed: " + jobName;
            String body = errorMessage.length() > 200
                    ? errorMessage.substring(0, 200) + "..."
                    : errorMessage;
            for (User admin : admins) {
                try {
                    userDeviceService.sendPush(admin.getId(), title, body);
                } catch (Exception e) {
                    log.error("Failed to push alert to admin {}: {}", admin.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to notify platform admins about job '{}' failure: {}", jobName, e.getMessage());
        }
    }
}
