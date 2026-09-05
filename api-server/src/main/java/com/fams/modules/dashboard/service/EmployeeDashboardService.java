package com.fams.modules.dashboard.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.EmployeeDashboardResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.repository.NotificationRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmployeeDashboardService {

    private final EmployeeRepository employeeRepository;
    private final AssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final SiteRepository siteRepository;
    private final CheckinRepository checkinRepository;
    private final AttendanceSummaryRepository attendanceSummaryRepository;
    private final NotificationRepository notificationRepository;
    private final ViolationRepository violationRepository;
    private final TenantRepository tenantRepository;

    public EmployeeDashboardService(EmployeeRepository employeeRepository,
                                     AssignmentRepository assignmentRepository,
                                     ShiftRepository shiftRepository,
                                     SiteRepository siteRepository,
                                     CheckinRepository checkinRepository,
                                     AttendanceSummaryRepository attendanceSummaryRepository,
                                     NotificationRepository notificationRepository,
                                     ViolationRepository violationRepository,
                                     TenantRepository tenantRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.attendanceSummaryRepository = attendanceSummaryRepository;
        this.notificationRepository = notificationRepository;
        this.violationRepository = violationRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public EmployeeDashboardResponse getDashboard(UUID tenantId, UUID callerUserId) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        UUID employeeId = employee.getId();
        // "Today" resolved in the tenant's own timezone (audit 2026-08-04) — a hardcoded UTC
        // boundary made "today's shifts"/"today's check-in" desync from the employee's actual
        // wall clock for hours around midnight UTC (e.g. all of early morning in Vietnam, UTC+7).
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId).orElse(null);
        ZoneId zone = ZoneId.of(tenant != null && tenant.getTimezone() != null ? tenant.getTimezone() : "UTC");
        LocalDate today = LocalDate.now(zone);

        List<EmployeeDashboardResponse.TodayShiftInfo> todayShifts = buildTodayShifts(tenantId, employeeId, today);
        EmployeeDashboardResponse.TodayCheckinStatus checkin = buildTodayCheckin(tenantId, employeeId, zone);
        EmployeeDashboardResponse.MonthlyAttendanceSummary monthly = buildMonthlyAttendance(tenantId, employeeId, today);
        EmployeeDashboardResponse.AlertsSummary alerts = buildAlerts(tenantId, employeeId, callerUserId);

        log.info("Employee dashboard fetched: tenantId={} employeeId={}", tenantId, employeeId);

        return EmployeeDashboardResponse.builder()
                .todayShifts(todayShifts)
                .checkin(checkin)
                .monthlyAttendance(monthly)
                .alerts(alerts)
                .build();
    }

    /** "Needs my attention" badge count for the home screen — same underlying data as
     *  GET /me/exceptions (pending_review check-ins + unresolved violations) plus unread
     *  notifications, so the employee sees a single number without an extra round-trip. */
    private EmployeeDashboardResponse.AlertsSummary buildAlerts(UUID tenantId, UUID employeeId, UUID callerUserId) {
        long unreadNotifications = notificationRepository.countUnreadByTenantAndUser(tenantId, callerUserId);
        long pendingCheckins = checkinRepository
                .countByTenantIdAndEmployeeIdAndStatusAndDeletedAtIsNull(tenantId, employeeId, "pending_review");
        long pendingViolations = violationRepository
                .countByTenantIdAndEmployeeIdAndResolvedFalseAndDeletedAtIsNull(tenantId, employeeId);

        return EmployeeDashboardResponse.AlertsSummary.builder()
                .unreadNotifications(unreadNotifications)
                .pendingExplanations(pendingCheckins + pendingViolations)
                .build();
    }

    private List<EmployeeDashboardResponse.TodayShiftInfo> buildTodayShifts(UUID tenantId, UUID employeeId,
                                                                               LocalDate today) {
        List<Assignment> assignments = assignmentRepository
                .findActiveAssignmentsForEmployeeOnDate(tenantId, employeeId, today,
                        DayOfWeekBitmask.bitForDate(today));

        return assignments.stream().map(a -> {
            Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(a.getSiteId(), tenantId)
                    .orElse(null);
            String siteName = site != null ? site.getName() : null;

            EmployeeDashboardResponse.ShiftDetail shiftDetail = null;
            if (a.getShiftId() != null) {
                Shift shift = shiftRepository.findById(a.getShiftId()).orElse(null);
                if (shift != null && shift.getDeletedAt() == null) {
                    shiftDetail = EmployeeDashboardResponse.ShiftDetail.builder()
                            .shiftId(shift.getId())
                            .name(shift.getName())
                            .startTime(shift.getStartTime())
                            .endTime(shift.getEndTime())
                            .build();
                }
            }

            return EmployeeDashboardResponse.TodayShiftInfo.builder()
                    .assignmentId(a.getId())
                    .siteId(a.getSiteId())
                    .siteName(siteName)
                    .role(a.getRole())
                    .shift(shiftDetail)
                    .build();
        }).collect(Collectors.toList());
    }

    private EmployeeDashboardResponse.TodayCheckinStatus buildTodayCheckin(UUID tenantId, UUID employeeId,
                                                                            ZoneId zone) {
        OffsetDateTime startOfDay = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime endOfDay = startOfDay.plusDays(1);

        List<CheckinRecord> todayCheckins = checkinRepository
                .findTodayCheckins(tenantId, employeeId, startOfDay, endOfDay);

        if (todayCheckins.isEmpty()) {
            return null;
        }

        CheckinRecord latest = todayCheckins.get(0);
        return EmployeeDashboardResponse.TodayCheckinStatus.builder()
                .checkinId(latest.getId())
                .siteId(latest.getSiteId())
                .status(latest.getStatus())
                .checkInAt(latest.getCheckInAt())
                .checkOutAt(latest.getCheckOutAt())
                .sessionCloseReason(latest.getSessionCloseReason())
                .sessionExpiresAt(latest.getSessionExpiresAt())
                .workMinutes(latest.getWorkMinutes())
                .open(latest.getCheckOutAt() == null
                        && latest.getSessionClosedAt() == null
                        && (latest.getSessionExpiresAt() == null
                            || OffsetDateTime.now().isBefore(latest.getSessionExpiresAt())))
                .build();
    }

    private EmployeeDashboardResponse.MonthlyAttendanceSummary buildMonthlyAttendance(UUID tenantId,
                                                                                        UUID employeeId,
                                                                                        LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        LocalDate firstOfNextMonth = firstOfMonth.plusMonths(1);

        List<AttendanceSummary> records = attendanceSummaryRepository
                .findByTenantIdAndEmployeeIdAndDateRange(tenantId, employeeId, firstOfMonth, firstOfNextMonth);

        int presentDays = 0;
        int lateDays = 0;
        int earlyLeaveDays = 0;
        int missingCheckoutDays = 0;
        int totalOtMinutes = 0;
        int totalWorkMinutes = 0;

        for (AttendanceSummary s : records) {
            presentDays++;
            if (s.isLate()) lateDays++;
            if (s.isEarlyLeave()) earlyLeaveDays++;
            if (s.isMissingCheckout()) missingCheckoutDays++;
            totalOtMinutes += s.getOtMinutes();
            totalWorkMinutes += s.getTotalWorkMinutes();
        }

        String month = String.format("%d-%02d", today.getYear(), today.getMonthValue());

        return EmployeeDashboardResponse.MonthlyAttendanceSummary.builder()
                .month(month)
                .presentDays(presentDays)
                .lateDays(lateDays)
                .earlyLeaveDays(earlyLeaveDays)
                .missingCheckoutDays(missingCheckoutDays)
                .totalOtMinutes(totalOtMinutes)
                .totalWorkMinutes(totalWorkMinutes)
                .build();
    }
}
