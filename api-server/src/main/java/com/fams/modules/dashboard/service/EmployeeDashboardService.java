package com.fams.modules.dashboard.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.EmployeeDashboardResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    public EmployeeDashboardService(EmployeeRepository employeeRepository,
                                     AssignmentRepository assignmentRepository,
                                     ShiftRepository shiftRepository,
                                     SiteRepository siteRepository,
                                     CheckinRepository checkinRepository,
                                     AttendanceSummaryRepository attendanceSummaryRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.attendanceSummaryRepository = attendanceSummaryRepository;
    }

    @Transactional(readOnly = true)
    public EmployeeDashboardResponse getDashboard(UUID tenantId, UUID callerUserId) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        UUID employeeId = employee.getId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<EmployeeDashboardResponse.TodayShiftInfo> todayShifts = buildTodayShifts(tenantId, employeeId, today);
        EmployeeDashboardResponse.TodayCheckinStatus checkin = buildTodayCheckin(tenantId, employeeId);
        EmployeeDashboardResponse.MonthlyAttendanceSummary monthly = buildMonthlyAttendance(tenantId, employeeId, today);

        log.info("Employee dashboard fetched: tenantId={} employeeId={}", tenantId, employeeId);

        return EmployeeDashboardResponse.builder()
                .todayShifts(todayShifts)
                .checkin(checkin)
                .monthlyAttendance(monthly)
                .build();
    }

    private List<EmployeeDashboardResponse.TodayShiftInfo> buildTodayShifts(UUID tenantId, UUID employeeId,
                                                                               LocalDate today) {
        List<Assignment> assignments = assignmentRepository
                .findActiveAssignmentsForEmployeeOnDate(tenantId, employeeId, today);

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

    private EmployeeDashboardResponse.TodayCheckinStatus buildTodayCheckin(UUID tenantId, UUID employeeId) {
        OffsetDateTime startOfDayUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endOfDayUtc = startOfDayUtc.plusDays(1);

        List<CheckinRecord> todayCheckins = checkinRepository
                .findTodayCheckins(tenantId, employeeId, startOfDayUtc, endOfDayUtc);

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
                .workMinutes(latest.getWorkMinutes())
                .open(latest.getCheckOutAt() == null)
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
