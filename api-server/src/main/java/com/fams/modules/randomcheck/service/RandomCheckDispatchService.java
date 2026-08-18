package com.fams.modules.randomcheck.service;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.dto.response.NotificationResponse;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.randomcheck.constant.RandomCheckEventTypes;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the dispatch of a single due random check.
 * Marks the check as 'sent' and triggers notification delivery.
 */
@Slf4j
@Service
public class RandomCheckDispatchService {

    private static final Pattern RESPONSE_WINDOW_PATTERN =
            Pattern.compile("\"responseWindowSeconds\"\\s*:\\s*(\\d+)");

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;

    public RandomCheckDispatchService(
            ScheduledCheckRepository scheduledCheckRepository,
            EmployeeRepository employeeRepository,
            @Lazy NotificationService notificationService) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.employeeRepository = employeeRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public void dispatch(UUID scheduledCheckId) {
        ScheduledCheck check = scheduledCheckRepository.findById(scheduledCheckId)
                .orElse(null);

        if (check == null || check.getDeletedAt() != null) {
            log.warn("dispatch skipped — check not found or deleted: {}", scheduledCheckId);
            return;
        }

        if (!"pending".equals(check.getStatus())) {
            log.debug("dispatch skipped — check {} already in status '{}'",
                    scheduledCheckId, check.getStatus());
            return;
        }

        check.setStatus("sent");
        check.setSentAt(OffsetDateTime.now());
        scheduledCheckRepository.save(check);

        log.info("Dispatched random check id={} employeeId={} siteId={}",
                scheduledCheckId, check.getEmployeeId(), check.getSiteId());

        sendNotification(check);
    }

    void sendNotification(ScheduledCheck check) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(check.getEmployeeId(), check.getTenantId())
                .orElse(null);

        if (employee == null || employee.getUserId() == null) {
            log.warn("Cannot send random check notification — employee or userId not found for checkId={}",
                    check.getId());
            return;
        }

        int windowSeconds = extractResponseWindow(check.getConfigSnapshot());
        String body = "Bạn có một yêu cầu kiểm tra ngẫu nhiên. "
                + "Vui lòng phản hồi trong vòng " + windowSeconds + " giây.";

        // Structured payload so the app can deep-link straight to this check instead of falling
        // back to opening a generic list — found missing via FE audit (2026-07-31).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("checkId", check.getId().toString());
        metadata.put("siteId", check.getSiteId().toString());
        metadata.put("expiresAt", check.getExpiresAt().toString());

        try {
            NotificationResponse notification = notificationService.createNotification(
                    check.getTenantId(),
                    employee.getUserId(),
                    RandomCheckEventTypes.RANDOM_CHECK_SENT,
                    "Kiểm tra ngẫu nhiên",
                    body,
                    metadata);
            // notification is null when in-app is disabled for this user/eventType (push-only
            // path) — see NotificationService#createNotification. Nothing to link back to then.
            if (notification != null) {
                check.setNotificationId(notification.getId());
                scheduledCheckRepository.save(check);
            }
            log.info("Random check notification sent to userId={} for checkId={}",
                    employee.getUserId(), check.getId());
        } catch (Exception e) {
            log.error("Failed to send notification for checkId={}: {}", check.getId(), e.getMessage(), e);
        }
    }

    private int extractResponseWindow(String configSnapshot) {
        if (configSnapshot == null) return 300;
        Matcher m = RESPONSE_WINDOW_PATTERN.matcher(configSnapshot);
        return m.find() ? Integer.parseInt(m.group(1)) : 300;
    }
}
