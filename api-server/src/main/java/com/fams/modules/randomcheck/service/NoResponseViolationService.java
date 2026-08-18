package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.modules.violation.service.ViolationNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class NoResponseViolationService {

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final ViolationRepository violationRepository;
    private final ViolationNotificationService violationNotificationService;

    public NoResponseViolationService(ScheduledCheckRepository scheduledCheckRepository,
                                      ViolationRepository violationRepository,
                                      ViolationNotificationService violationNotificationService) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.violationRepository = violationRepository;
        this.violationNotificationService = violationNotificationService;
    }

    /**
     * Scans all tenants for expired sent checks and creates no_response violations.
     * Called by the scheduled job.
     */
    @Transactional
    public int processAllExpired() {
        List<ScheduledCheck> expired = scheduledCheckRepository.findExpiredSentChecks(OffsetDateTime.now());
        return processChecks(expired);
    }

    /**
     * Scans a single tenant for expired sent checks and creates no_response violations.
     * Used by the manual trigger endpoint for testing.
     */
    @Transactional
    public int processExpiredForTenant(UUID tenantId) {
        List<ScheduledCheck> expired =
                scheduledCheckRepository.findExpiredSentChecksForTenant(tenantId, OffsetDateTime.now());
        return processChecks(expired);
    }

    private int processChecks(List<ScheduledCheck> expired) {
        if (expired.isEmpty()) return 0;

        int count = 0;
        for (ScheduledCheck check : expired) {
            // Idempotency guard — skip if violation already exists for this check
            if (violationRepository.existsByScheduledCheckIdAndViolationType(
                    check.getId(), "no_response")) {
                check.setStatus("no_response");
                scheduledCheckRepository.save(check);
                continue;
            }

            check.setStatus("no_response");
            scheduledCheckRepository.save(check);

            Violation violation = Violation.builder()
                    .tenantId(check.getTenantId())
                    .employeeId(check.getEmployeeId())
                    .siteId(check.getSiteId())
                    .scheduledCheckId(check.getId())
                    .violationType("no_response")
                    .checkDate(check.getCheckDate())
                    .description("No response to random check by " + check.getExpiresAt())
                    .resolved(false)
                    .build();

            violationRepository.save(violation);
            count++;

            violationNotificationService.notifyRandomCheckViolation(
                    check.getTenantId(), check.getEmployeeId(), check.getSiteId(), "no_response");

            log.info("no_response violation created: checkId={} employeeId={} siteId={}",
                    check.getId(), check.getEmployeeId(), check.getSiteId());
        }

        if (count > 0) {
            log.info("Processed {} expired check(s) → no_response violations", count);
        }
        return count;
    }
}
