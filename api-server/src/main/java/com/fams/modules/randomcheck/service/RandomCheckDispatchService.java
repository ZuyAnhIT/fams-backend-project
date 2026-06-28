package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles the dispatch of a single due random check.
 * Marks the check as 'sent' and triggers notification delivery (task 100).
 */
@Slf4j
@Service
public class RandomCheckDispatchService {

    private final ScheduledCheckRepository scheduledCheckRepository;

    public RandomCheckDispatchService(ScheduledCheckRepository scheduledCheckRepository) {
        this.scheduledCheckRepository = scheduledCheckRepository;
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
        scheduledCheckRepository.save(check);

        // Notification delivery will be added in task 100 (push/in-app notification)
        log.info("Dispatched random check id={} employeeId={} siteId={}",
                scheduledCheckId, check.getEmployeeId(), check.getSiteId());
    }
}
