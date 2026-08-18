package com.fams.modules.dashboard.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.SupervisorDashboardResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SupervisorDashboardService {

    private final EmployeeRepository employeeRepository;
    private final AssignmentRepository assignmentRepository;
    private final SiteRepository siteRepository;
    private final CheckinRepository checkinRepository;
    private final TenantRepository tenantRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final ViolationRepository violationRepository;

    public SupervisorDashboardService(EmployeeRepository employeeRepository,
                                       AssignmentRepository assignmentRepository,
                                       SiteRepository siteRepository,
                                       CheckinRepository checkinRepository,
                                       TenantRepository tenantRepository,
                                       ScheduledCheckRepository scheduledCheckRepository,
                                       ViolationRepository violationRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.tenantRepository = tenantRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.violationRepository = violationRepository;
    }

    @Transactional(readOnly = true)
    public SupervisorDashboardResponse getDashboard(UUID tenantId, UUID callerUserId) {
        Employee supervisor = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId).orElse(null);
        ZoneId zone = ZoneId.of(tenant != null && tenant.getTimezone() != null ? tenant.getTimezone() : "UTC");
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime startOfToday = today.atStartOfDay(zone).toOffsetDateTime();

        List<Assignment> supervisorAssignments = assignmentRepository
                .findActiveSupervisorAssignmentsForEmployee(tenantId, supervisor.getId(), today,
                        DayOfWeekBitmask.bitForDate(today));

        List<SupervisorDashboardResponse.SiteStatus> siteStatuses = supervisorAssignments.stream()
                .map(a -> buildSiteStatus(tenantId, a.getSiteId(), today, startOfToday))
                .collect(Collectors.toList());

        log.info("Supervisor dashboard fetched: tenantId={} supervisorId={} sites={}",
                tenantId, supervisor.getId(), siteStatuses.size());

        return SupervisorDashboardResponse.builder()
                .supervisedSites(siteStatuses)
                .build();
    }

    private SupervisorDashboardResponse.SiteStatus buildSiteStatus(UUID tenantId, UUID siteId,
                                                                     LocalDate today, OffsetDateTime startOfToday) {
        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElse(null);
        String siteName = site != null ? site.getName() : null;

        int expected = assignmentRepository
                .findActiveAssignmentsForSiteOnDate(tenantId, siteId, today,
                        DayOfWeekBitmask.bitForDate(today)).size();

        // Bounded to sessions opened today — an employee who forgot to check out days ago must
        // not still show as "on-site now" to the supervisor (audit 2026-08-04, same guard as
        // CheckinRepository#countOpenSessions).
        List<CheckinRecord> openSessions = checkinRepository.findOpenSessionsBySite(tenantId, siteId, startOfToday);

        List<SupervisorDashboardResponse.OnSiteEmployee> onSiteEmployees = new ArrayList<>();
        for (CheckinRecord record : openSessions) {
            Employee emp = employeeRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(record.getEmployeeId(), tenantId)
                    .orElse(null);
            onSiteEmployees.add(SupervisorDashboardResponse.OnSiteEmployee.builder()
                    .employeeId(record.getEmployeeId())
                    .firstName(emp != null ? emp.getFirstName() : null)
                    .lastName(emp != null ? emp.getLastName() : null)
                    .employeeCode(emp != null ? emp.getEmployeeCode() : null)
                    .checkinId(record.getId())
                    .checkInAt(record.getCheckInAt())
                    .checkInLat(record.getCheckInLat())
                    .checkInLon(record.getCheckInLon())
                    .build());
        }

        // #130 (docs/api/backend-feature-audit-2026-08-07.md): before this fix, a site's
        // coordinates and its on-site employees' check-in coordinates were only ever available
        // from two separate calls (GET /sites and GET /checkin) — no single response could
        // drive a "site map with who's on it" screen. This is the natural home for that: the
        // supervisor dashboard already aggregates open sessions per supervised site.
        long randomCheckPending = scheduledCheckRepository.countActiveBySite(tenantId, siteId);
        long unresolvedViolations = violationRepository.countUnresolved(tenantId, siteId);

        return SupervisorDashboardResponse.SiteStatus.builder()
                .siteId(siteId)
                .siteName(siteName)
                .expectedToday(expected)
                .onSiteNow(openSessions.size())
                .onSiteEmployees(onSiteEmployees)
                .siteLatitude(site != null ? site.getLatitude() : null)
                .siteLongitude(site != null ? site.getLongitude() : null)
                .randomCheckPending(randomCheckPending)
                .unresolvedViolations(unresolvedViolations)
                .build();
    }
}
