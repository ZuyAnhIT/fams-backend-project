package com.fams.modules.attendance.service;

import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceEmployeeDataIsolationTest {

    @Mock AttendanceSummaryRepository summaryRepository;
    @Mock CheckinRepository checkinRepository;
    @Mock SiteRepository siteRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock SiteScopeService siteScopeService;
    @Mock AuditLogService auditLogService;
    @Mock ScheduledCheckRepository scheduledCheckRepository;
    @Mock RandomCheckConfigRepository randomCheckConfigRepository;
    @Mock NotificationService notificationService;

    private AttendanceSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceSummaryService(
                summaryRepository, checkinRepository, siteRepository, employeeRepository,
                userRoleRepository, siteScopeService, auditLogService, scheduledCheckRepository,
                randomCheckConfigRepository, notificationService);
    }

    @Test
    void employeeReadPermissionCannotOpenAnotherEmployeesAttendanceSummary() {
        UUID tenantId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();
        UUID callerEmployeeId = UUID.randomUUID();
        UUID otherEmployeeId = UUID.randomUUID();
        UUID summaryId = UUID.randomUUID();

        when(userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId))
                .thenReturn(Set.of("attendance:read"));
        when(summaryRepository.findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId))
                .thenReturn(Optional.of(AttendanceSummary.builder()
                        .id(summaryId)
                        .tenantId(tenantId)
                        .employeeId(otherEmployeeId)
                        .siteId(UUID.randomUUID())
                        .build()));
        when(employeeRepository.findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId))
                .thenReturn(Optional.of(Employee.builder().id(callerEmployeeId).build()));

        assertThatThrownBy(() -> service.getSummary(tenantId, summaryId, callerUserId, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("only view your own");

        verify(siteScopeService, never()).isSiteAllowed(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
