package com.fams.modules.randomcheck.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ScheduledCheckCancelService {

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final RandomCheckDispatchQueue dispatchQueue;
    private final AuditLogService auditLogService;

    public ScheduledCheckCancelService(ScheduledCheckRepository scheduledCheckRepository,
                                       RandomCheckDispatchQueue dispatchQueue,
                                       AuditLogService auditLogService) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.dispatchQueue = dispatchQueue;
        this.auditLogService = auditLogService;
    }

    /**
     * HR/Admin cancels a single scheduled check by ID.
     * Only pending or sent checks may be cancelled; others are rejected with IllegalStateException.
     *
     * @param callerId who is cancelling — recorded on cancelledBy + used for the audit log entry
     * @param reason optional free-text reason (#99, 2026-08-18) — null/blank is allowed, the
     *               field on the check simply stays empty
     */
    @Transactional
    public void cancelCheck(UUID tenantId, UUID checkId, UUID callerId, String reason) {
        ScheduledCheck check = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Scheduled check not found: " + checkId));

        String oldStatus = check.getStatus();
        if ("cancelled".equals(oldStatus) || "responded".equals(oldStatus) || "no_response".equals(oldStatus)) {
            throw new IllegalStateException(
                    "Cannot cancel a check with status '" + oldStatus + "'");
        }

        check.setStatus("cancelled");
        check.setCancelledBy(callerId);
        check.setCancelledAt(OffsetDateTime.now());
        check.setCancelledReason(reason);
        scheduledCheckRepository.save(check);
        dispatchQueue.cancel(checkId);

        log.info("Scheduled check cancelled manually: id={} tenantId={} by={} reason={}",
                checkId, tenantId, callerId, reason);

        try {
            Map<String, Object> oldValue = new LinkedHashMap<>();
            oldValue.put("status", oldStatus);
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("status", "cancelled");
            newValue.put("reason", reason == null ? "" : reason);
            auditLogService.record(
                    tenantId, callerId, null,
                    "ScheduledCheck", checkId.toString(), "scheduled_check_cancelled",
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(), null, null);
        } catch (Exception e) {
            log.warn("Failed to record audit log for scheduled_check_cancelled checkId={}: {}",
                    checkId, e.getMessage());
        }
    }

    /**
     * Cascading cancellation: cancels all pending/sent checks for an assignment, triggered as a
     * side effect of an assignment being cancelled or an employee being terminated — not a
     * direct HR action on the checks themselves. `cancelledBy` is still set to the human who
     * caused the cascade (the one who cancelled the assignment / terminated the employee), not
     * left null, so HR reviewing a check's cancellation trail can see a real actor rather than
     * an unexplained system action.
     *
     * @param callerId who triggered the cascade (assignment-canceller or terminator)
     * @param reason short machine label for why, e.g. "Assignment cancelled" — distinct from a
     *               free-text HR-entered reason, this one is always one of a small fixed set of
     *               known causes
     */
    @Transactional
    public int cancelPendingByAssignment(UUID tenantId, UUID assignmentId, UUID callerId, String reason) {
        List<ScheduledCheck> cancellable = scheduledCheckRepository.findCancellableByAssignment(assignmentId);
        if (cancellable.isEmpty()) return 0;

        OffsetDateTime now = OffsetDateTime.now();
        for (ScheduledCheck check : cancellable) {
            String oldStatus = check.getStatus();
            check.setStatus("cancelled");
            check.setCancelledBy(callerId);
            check.setCancelledAt(now);
            check.setCancelledReason(reason);
            dispatchQueue.cancel(check.getId());

            try {
                Map<String, Object> oldValue = new LinkedHashMap<>();
                oldValue.put("status", oldStatus);
                Map<String, Object> newValue = new LinkedHashMap<>();
                newValue.put("status", "cancelled");
                newValue.put("reason", reason);
                newValue.put("assignmentId", assignmentId.toString());
                auditLogService.record(
                        tenantId, callerId, null,
                        "ScheduledCheck", check.getId().toString(), "scheduled_check_cancelled",
                        oldValue, newValue,
                        HttpRequestUtils.currentRequestId(), null, null);
            } catch (Exception e) {
                log.warn("Failed to record audit log for cascading scheduled_check_cancelled "
                        + "checkId={}: {}", check.getId(), e.getMessage());
            }
        }
        scheduledCheckRepository.saveAll(cancellable);

        log.info("Auto-cancelled {} scheduled check(s) for assignmentId={} reason={} by={}",
                cancellable.size(), assignmentId, reason, callerId);
        return cancellable.size();
    }
}
