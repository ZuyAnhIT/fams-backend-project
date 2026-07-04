package com.fams.modules.dashboard.service;

import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.HrDashboardResponse;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.violation.repository.ViolationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class HrDashboardService {

    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final CheckinRepository checkinRepository;
    private final AttendanceSummaryRepository attendanceSummaryRepository;
    private final ViolationRepository violationRepository;
    private final UserRoleRepository userRoleRepository;

    public HrDashboardService(EmployeeRepository employeeRepository,
                               SiteRepository siteRepository,
                               CheckinRepository checkinRepository,
                               AttendanceSummaryRepository attendanceSummaryRepository,
                               ViolationRepository violationRepository,
                               UserRoleRepository userRoleRepository) {
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.attendanceSummaryRepository = attendanceSummaryRepository;
        this.violationRepository = violationRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public HrDashboardResponse getDashboard(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to view the HR dashboard");
            }
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime firstOfMonth = today.withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        HrDashboardResponse.PersonnelOverview personnel = buildPersonnel(tenantId, firstOfMonth);
        HrDashboardResponse.AttendanceOverview attendance = buildAttendance(tenantId, today);
        HrDashboardResponse.ViolationOverview violations = buildViolations(tenantId, firstOfMonth);
        HrDashboardResponse.SiteOverview sites = buildSites(tenantId);

        log.info("HR dashboard fetched: tenantId={} by userId={}", tenantId, callerUserId);

        return HrDashboardResponse.builder()
                .personnel(personnel)
                .attendance(attendance)
                .violations(violations)
                .sites(sites)
                .build();
    }

    private HrDashboardResponse.PersonnelOverview buildPersonnel(UUID tenantId, OffsetDateTime firstOfMonth) {
        long total = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        long newCount = employeeRepository.countNewSince(tenantId, firstOfMonth);
        return HrDashboardResponse.PersonnelOverview.builder()
                .totalEmployees(total)
                .newThisMonth(newCount)
                .build();
    }

    private HrDashboardResponse.AttendanceOverview buildAttendance(UUID tenantId, LocalDate today) {
        long present = attendanceSummaryRepository.countByTenantAndDate(tenantId, today);
        long late = attendanceSummaryRepository.countLateByTenantAndDate(tenantId, today);
        long onSite = checkinRepository.countOpenSessions(tenantId);
        return HrDashboardResponse.AttendanceOverview.builder()
                .presentToday(present)
                .lateToday(late)
                .onSiteNow(onSite)
                .build();
    }

    private HrDashboardResponse.ViolationOverview buildViolations(UUID tenantId, OffsetDateTime firstOfMonth) {
        long unresolved = violationRepository.countUnresolved(tenantId);
        long resolvedThisMonth = violationRepository.countResolvedSince(tenantId, firstOfMonth);

        List<Object[]> rows = violationRepository.countUnresolvedByType(tenantId);
        Map<String, Long> byType = new HashMap<>();
        for (Object[] row : rows) {
            byType.put((String) row[0], (Long) row[1]);
        }

        return HrDashboardResponse.ViolationOverview.builder()
                .unresolved(unresolved)
                .resolvedThisMonth(resolvedThisMonth)
                .unresolvedByType(byType)
                .build();
    }

    private HrDashboardResponse.SiteOverview buildSites(UUID tenantId) {
        long total = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        long onSite = checkinRepository.countOpenSessions(tenantId);
        return HrDashboardResponse.SiteOverview.builder()
                .totalSites(total)
                .employeesOnSiteNow(onSite)
                .build();
    }
}
