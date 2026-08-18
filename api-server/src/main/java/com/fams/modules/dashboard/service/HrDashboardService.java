package com.fams.modules.dashboard.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
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
    private final AssignmentRepository assignmentRepository;

    public HrDashboardService(EmployeeRepository employeeRepository,
                               SiteRepository siteRepository,
                               CheckinRepository checkinRepository,
                               AttendanceSummaryRepository attendanceSummaryRepository,
                               ViolationRepository violationRepository,
                               UserRoleRepository userRoleRepository,
                               TenantRepository tenantRepository,
                               AssignmentRepository assignmentRepository) {
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.attendanceSummaryRepository = attendanceSummaryRepository;
        this.violationRepository = violationRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional(readOnly = true)
    public HrDashboardResponse getDashboard(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        return getDashboard(tenantId, callerUserId, callerIsPlatformAdmin, null);
    }

    /** @param siteId found via audit (2026-08-18): AC calls for a workspace/site filter on every
     *  metric, previously absent entirely. Null means tenant-wide (unchanged default behavior). */
    @Transactional(readOnly = true)
    public HrDashboardResponse getDashboard(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin,
                                            UUID siteId) {
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
        long onSiteNow = checkinRepository.countOpenSessions(tenantId, startOfToday, siteId);

        HrDashboardResponse.PersonnelOverview personnel = buildPersonnel(tenantId, firstOfMonth, siteId);
        HrDashboardResponse.AttendanceOverview attendance = buildAttendance(tenantId, today, onSiteNow, siteId);
        HrDashboardResponse.ViolationOverview violations = buildViolations(tenantId, firstOfMonth, siteId);
        HrDashboardResponse.SiteOverview sites = buildSites(tenantId, onSiteNow, siteId);

        log.info("HR dashboard fetched: tenantId={} siteId={} by userId={}", tenantId, siteId, callerUserId);

        return HrDashboardResponse.builder()
                .personnel(personnel)
                .attendance(attendance)
                .violations(violations)
                .sites(sites)
                .build();
    }

    /** Employee has no siteId of its own (site relationship is via Assignment, many-to-many over
     *  time) — resolve the site-scoped employee-ID set first, same pattern EmployeeService
     *  already uses for site-scoped callers (AssignmentRepository#findDistinctEmployeeIdsByTenantIdAndSiteIdIn). */
    private HrDashboardResponse.PersonnelOverview buildPersonnel(UUID tenantId, OffsetDateTime firstOfMonth, UUID siteId) {
        if (siteId == null) {
            long total = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
            long newCount = employeeRepository.countNewSince(tenantId, firstOfMonth);
            return HrDashboardResponse.PersonnelOverview.builder()
                    .totalEmployees(total)
                    .newThisMonth(newCount)
                    .build();
        }

        Set<UUID> employeeIds = assignmentRepository
                .findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, List.of(siteId));
        if (employeeIds.isEmpty()) {
            return HrDashboardResponse.PersonnelOverview.builder().totalEmployees(0).newThisMonth(0).build();
        }
        long total = employeeRepository.countByTenantIdAndIdInAndDeletedAtIsNull(tenantId, employeeIds);
        long newCount = employeeRepository.countNewSinceByIdIn(tenantId, employeeIds, firstOfMonth);
        return HrDashboardResponse.PersonnelOverview.builder()
                .totalEmployees(total)
                .newThisMonth(newCount)
                .build();
    }

    private HrDashboardResponse.AttendanceOverview buildAttendance(UUID tenantId, LocalDate today, long onSiteNow,
                                                                    UUID siteId) {
        long present = attendanceSummaryRepository.countByTenantAndDate(tenantId, today, siteId);
        long late = attendanceSummaryRepository.countLateByTenantAndDate(tenantId, today, siteId);
        long pendingReview = checkinRepository.countPendingReview(tenantId, siteId);
        long missingCheckoutToday = attendanceSummaryRepository.countMissingCheckoutByTenantAndDate(tenantId, today, siteId);
        return HrDashboardResponse.AttendanceOverview.builder()
                .presentToday(present)
                .lateToday(late)
                .onSiteNow(onSiteNow)
                .pendingReview(pendingReview)
                .missingCheckoutToday(missingCheckoutToday)
                .build();
    }

    private HrDashboardResponse.ViolationOverview buildViolations(UUID tenantId, OffsetDateTime firstOfMonth, UUID siteId) {
        long unresolved = violationRepository.countUnresolved(tenantId, siteId);
        long resolvedThisMonth = violationRepository.countResolvedSince(tenantId, firstOfMonth, siteId);

        List<Object[]> rows = violationRepository.countUnresolvedByType(tenantId, siteId);
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

    private HrDashboardResponse.SiteOverview buildSites(UUID tenantId, long onSiteNow, UUID siteId) {
        long total = siteId != null ? 1 : siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        return HrDashboardResponse.SiteOverview.builder()
                .totalSites(total)
                .employeesOnSiteNow(onSiteNow)
                .build();
    }
}
