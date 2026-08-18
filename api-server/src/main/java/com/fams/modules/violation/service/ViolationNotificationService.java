package com.fams.modules.violation.service;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.violation.constant.ViolationEventTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * #106/#107 (2026-08-18): random check violation creation (no_response, location_fail, face_fail,
 * liveness_fail) previously created zero notifications — HR and the employee both had to
 * proactively open the app/Web Admin to discover a violation had happened. Shared by
 * {@code NoResponseViolationService} and {@code CheckResponseService} so both violation-creation
 * paths notify the same way instead of duplicating the logic — mirrors the exact pattern already
 * proven for missing-checkout notifications (AttendanceSummaryService#notifyMissingCheckout,
 * 2026-08-17): one message to the employee, one broadcast to every tenant holder of
 * {@code violations:list} (HR/Admin), best-effort (a notification failure must never roll back
 * the violation itself).
 */
@Slf4j
@Service
public class ViolationNotificationService {

    private static final Map<String, String> VIOLATION_LABELS = Map.of(
            "no_response", "không phản hồi kiểm tra ngẫu nhiên",
            "location_fail", "sai vị trí khi kiểm tra ngẫu nhiên",
            "face_fail", "không xác thực được khuôn mặt khi kiểm tra ngẫu nhiên",
            "liveness_fail", "không xác thực được người thật khi kiểm tra ngẫu nhiên");

    private final NotificationService notificationService;
    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final UserRoleRepository userRoleRepository;

    public ViolationNotificationService(@Lazy NotificationService notificationService,
                                        EmployeeRepository employeeRepository,
                                        SiteRepository siteRepository,
                                        UserRoleRepository userRoleRepository) {
        this.notificationService = notificationService;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.userRoleRepository = userRoleRepository;
    }

    public void notifyRandomCheckViolation(UUID tenantId, UUID employeeId, UUID siteId, String violationType) {
        try {
            Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                    .orElse(null);
            String empName = employee != null ? (employee.getFirstName() + " " + employee.getLastName()).trim() : null;
            String siteName = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                    .map(Site::getName).orElse(null);
            String label = VIOLATION_LABELS.getOrDefault(violationType, violationType);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("employeeId", employeeId.toString());
            metadata.put("siteId", siteId.toString());
            metadata.put("violationType", violationType);

            if (employee != null && employee.getUserId() != null) {
                String empBody = "Bạn vừa bị ghi nhận vi phạm: " + label
                        + (siteName != null ? " tại " + siteName : "") + ".";
                notificationService.createNotification(
                        tenantId, employee.getUserId(),
                        ViolationEventTypes.RANDOM_CHECK_VIOLATION_EMPLOYEE,
                        "Vi phạm kiểm tra ngẫu nhiên",
                        empBody, metadata);
            }

            Set<UUID> hrUserIds = userRoleRepository
                    .findDistinctActiveHolderIdsOfPermissionInTenant(tenantId, "violations:list");
            String hrBody = (empName != null ? empName : "Một nhân viên") + " vừa bị ghi nhận vi phạm: " + label
                    + (siteName != null ? " tại " + siteName : "") + ".";
            for (UUID hrUserId : hrUserIds) {
                if (employee != null && hrUserId.equals(employee.getUserId())) continue;
                notificationService.createNotification(
                        tenantId, hrUserId,
                        ViolationEventTypes.RANDOM_CHECK_VIOLATION_HR,
                        "Vi phạm kiểm tra ngẫu nhiên",
                        hrBody, metadata);
            }
        } catch (Exception e) {
            log.warn("Failed to send violation notification tenantId={} employeeId={} violationType={}: {}",
                    tenantId, employeeId, violationType, e.getMessage());
        }
    }
}
