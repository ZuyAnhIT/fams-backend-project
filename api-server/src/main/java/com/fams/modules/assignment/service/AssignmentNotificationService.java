package com.fams.modules.assignment.service;

import com.fams.modules.assignment.constant.AssignmentEventTypes;
import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * #18/#19 (2026-09-03): notifies the assigned employee when a site/shift assignment is created or
 * cancelled. Previously the assignment module sent nothing — the worker had to proactively open
 * the App to notice a new posting, and there was no push at all.
 *
 * <p>Mirrors {@code ViolationNotificationService}: best-effort (a notification failure must never
 * roll back the assignment write), one message to the employee, routed through
 * {@link NotificationService#createNotification} so it lands in the in-app inbox and — because
 * push defaults to opt-in — is delivered as an FCM push the OS shows while the App is closed.
 * The metadata carries {@code assignmentId}/{@code siteId}/{@code shiftId} so the App can
 * deep-link straight to the check-in screen (see {@code resolveNotificationHref}).
 */
@Slf4j
@Service
public class AssignmentNotificationService {

    private final NotificationService notificationService;
    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;

    public AssignmentNotificationService(@Lazy NotificationService notificationService,
                                         EmployeeRepository employeeRepository,
                                         SiteRepository siteRepository,
                                         ShiftRepository shiftRepository) {
        this.notificationService = notificationService;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
    }

    /** Assignment just created — tell the worker where/when they are now expected. */
    public void notifyAssignmentCreated(Assignment assignment) {
        deliver(assignment,
                AssignmentEventTypes.ASSIGNMENT_CREATED_EMPLOYEE,
                "Bạn được phân công công trình mới",
                "Bạn vừa được phân công");
    }

    /** Assignment cancelled — tell the worker they are no longer expected there. */
    public void notifyAssignmentCancelled(Assignment assignment) {
        deliver(assignment,
                AssignmentEventTypes.ASSIGNMENT_CANCELLED_EMPLOYEE,
                "Phân công công trình đã kết thúc",
                "Phân công đã kết thúc");
    }

    private void deliver(Assignment assignment, String eventType, String title, String bodyPrefix) {
        try {
            UUID tenantId = assignment.getTenantId();
            Employee employee = employeeRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(assignment.getEmployeeId(), tenantId)
                    .orElse(null);
            if (employee == null || employee.getUserId() == null) {
                // No linked account (invited-but-not-registered) — nothing to push to.
                return;
            }

            String siteName = siteRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(assignment.getSiteId(), tenantId)
                    .map(Site::getName)
                    .orElse("công trình");

            String shiftText = "";
            if (assignment.getShiftId() != null) {
                Shift shift = shiftRepository.findById(assignment.getShiftId()).orElse(null);
                if (shift != null && shift.getDeletedAt() == null) {
                    shiftText = " · ca " + shift.getName() + " ("
                            + shift.getStartTime().toString().substring(0, 5) + "–"
                            + shift.getEndTime().toString().substring(0, 5) + ")";
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("assignmentId", assignment.getId().toString());
            metadata.put("siteId", assignment.getSiteId().toString());
            if (assignment.getShiftId() != null) {
                metadata.put("shiftId", assignment.getShiftId().toString());
            }

            String body = bodyPrefix + " tại " + siteName + shiftText + ".";
            notificationService.createNotification(tenantId, employee.getUserId(), eventType, title, body, metadata);
        } catch (Exception e) {
            log.warn("Failed to send assignment notification assignmentId={} eventType={}: {}",
                    assignment.getId(), eventType, e.getMessage());
        }
    }
}
