package com.fams.modules.dashboard.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.dashboard.dto.response.SupervisorDashboardResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
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

    public SupervisorDashboardService(EmployeeRepository employeeRepository,
                                       AssignmentRepository assignmentRepository,
                                       SiteRepository siteRepository,
                                       CheckinRepository checkinRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
    }

    @Transactional(readOnly = true)
    public SupervisorDashboardResponse getDashboard(UUID tenantId, UUID callerUserId) {
        Employee supervisor = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Assignment> supervisorAssignments = assignmentRepository
                .findActiveSupervisorAssignmentsForEmployee(tenantId, supervisor.getId(), today,
                        DayOfWeekBitmask.bitForDate(today));

        List<SupervisorDashboardResponse.SiteStatus> siteStatuses = supervisorAssignments.stream()
                .map(a -> buildSiteStatus(tenantId, a.getSiteId(), today))
                .collect(Collectors.toList());

        log.info("Supervisor dashboard fetched: tenantId={} supervisorId={} sites={}",
                tenantId, supervisor.getId(), siteStatuses.size());

        return SupervisorDashboardResponse.builder()
                .supervisedSites(siteStatuses)
                .build();
    }

    private SupervisorDashboardResponse.SiteStatus buildSiteStatus(UUID tenantId, UUID siteId,
                                                                     LocalDate today) {
        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElse(null);
        String siteName = site != null ? site.getName() : null;

        int expected = assignmentRepository
                .findActiveAssignmentsForSiteOnDate(tenantId, siteId, today,
                        DayOfWeekBitmask.bitForDate(today)).size();

        List<CheckinRecord> openSessions = checkinRepository.findOpenSessionsBySite(tenantId, siteId);

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
                    .build());
        }

        return SupervisorDashboardResponse.SiteStatus.builder()
                .siteId(siteId)
                .siteName(siteName)
                .expectedToday(expected)
                .onSiteNow(openSessions.size())
                .onSiteEmployees(onSiteEmployees)
                .build();
    }
}
