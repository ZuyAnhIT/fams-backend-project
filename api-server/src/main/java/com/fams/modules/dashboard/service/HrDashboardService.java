package com.fams.modules.dashboard.service;

import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.HrDashboardResponse;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.violation.repository.ViolationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private final TenantRepository tenantRepository;

    public HrDashboardService(EmployeeRepository employeeRepository,
                               SiteRepository siteRepository,
                               CheckinRepository checkinRepository,
                               AttendanceSummaryRepository attendanceSummaryRepository,
                               ViolationRepository violationRepository,
                               UserRoleRepository userRoleRepository,
                               TenantRepository tenantRepository) {
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.attendanceSummaryRepository = attendanceSummaryRepository;
        this.violationRepository = violationRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public HrDashboardResponse getDashboard(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to view the HR dashboard");
            }
        }

        // "Today" and "this month" are computed in the tenant's own timezone (audit 2026-08-04)
        // — using a hardcoded UTC boundary meant every tenant's morning shift (e.g. Vietnam,
        // UTC+7) started counting as "today" up to 7 hours late by the wall clock HR actually
        // works on, understating presentToday/lateToday right when they matter most.
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId).orElse(null);
        ZoneId zone = ZoneId.of(tenant != null && tenant.getTimezone() != null ? tenant.getTimezone() : "UTC");
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime startOfToday = today.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime firstOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toOffsetDateTime();

        // Computed once and shared — "on-site now" means the same thing in both the attendance
        // and site overview sections, no reason to run the identical query twice per request.
        long onSiteNow = checkinRepository.countOpenSessions(tenantId, startOfToday);

        HrDashboardResponse.PersonnelOverview personnel = buildPersonnel(tenantId, firstOfMonth);
        HrDashboardResponse.AttendanceOverview attendance = buildAttendance(tenantId, today, onSiteNow);
        HrDashboardResponse.ViolationOverview violations = buildViolations(tenantId, firstOfMonth);
        HrDashboardResponse.SiteOverview sites = buildSites(tenantId, onSiteNow);

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

    private HrDashboardResponse.AttendanceOverview buildAttendance(UUID tenantId, LocalDate today, long onSiteNow) {
        long present = attendanceSummaryRepository.countByTenantAndDate(tenantId, today);
        long late = attendanceSummaryRepository.countLateByTenantAndDate(tenantId, today);
        return HrDashboardResponse.AttendanceOverview.builder()
                .presentToday(present)
                .lateToday(late)
                .onSiteNow(onSiteNow)
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

    private HrDashboardResponse.SiteOverview buildSites(UUID tenantId, long onSiteNow) {
        long total = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        return HrDashboardResponse.SiteOverview.builder()
                .totalSites(total)
                .employeesOnSiteNow(onSiteNow)
                .build();
    }
}
