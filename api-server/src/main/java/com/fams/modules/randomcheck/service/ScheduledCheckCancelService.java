package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ScheduledCheckCancelService {

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final RandomCheckDispatchQueue dispatchQueue;

    public ScheduledCheckCancelService(ScheduledCheckRepository scheduledCheckRepository,
                                       RandomCheckDispatchQueue dispatchQueue) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.dispatchQueue = dispatchQueue;
    }

    /**
     * HR/Admin cancels a single scheduled check by ID.
     * Only pending or sent checks may be cancelled; others are rejected with IllegalStateException.
     */
    @Transactional
    public void cancelCheck(UUID tenantId, UUID checkId) {
        ScheduledCheck check = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Scheduled check not found: " + checkId));

        String status = check.getStatus();
        if ("cancelled".equals(status) || "responded".equals(status) || "no_response".equals(status)) {
            throw new IllegalStateException(
                    "Cannot cancel a check with status '" + status + "'");
        }

        check.setStatus("cancelled");
        scheduledCheckRepository.save(check);
        dispatchQueue.cancel(checkId);

        log.info("Scheduled check cancelled manually: id={} tenantId={}", checkId, tenantId);
    }

    /**
     * System-triggered: cancels all pending/sent checks for an assignment.
     * Called when an assignment is cancelled so stale checks are never dispatched.
     */
    @Transactional
    public int cancelPendingByAssignment(UUID assignmentId) {
        List<ScheduledCheck> cancellable = scheduledCheckRepository.findCancellableByAssignment(assignmentId);
        if (cancellable.isEmpty()) return 0;

        for (ScheduledCheck check : cancellable) {
            check.setStatus("cancelled");
            dispatchQueue.cancel(check.getId());
        }
        scheduledCheckRepository.saveAll(cancellable);

        log.info("Auto-cancelled {} scheduled check(s) for assignmentId={}", cancellable.size(), assignmentId);
        return cancellable.size();
    }
}
