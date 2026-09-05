package com.fams.modules.assignment.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceExactSelectionTest {

    @Mock AssignmentRepository assignmentRepository;
    @Mock SiteRepository siteRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock ShiftRepository shiftRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock SiteScopeService siteScopeService;
    @Mock ScheduledCheckCancelService scheduledCheckCancelService;
    @Mock AuditLogService auditLogService;
    @Mock AssignmentNotificationService assignmentNotificationService;

    @InjectMocks AssignmentService service;

    @Test
    void resolvesTheExactAssignmentSelectedByTheApp() {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID selectedAssignmentId = UUID.randomUUID();
        LocalDate vietnamToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Assignment selected = Assignment.builder()
                .id(selectedAssignmentId)
                .tenantId(tenantId)
                .employeeId(employeeId)
                .siteId(siteId)
                .startDate(vietnamToday.minusDays(1))
                .endDate(vietnamToday.plusDays(1))
                .status("active")
                .build();

        when(assignmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(selectedAssignmentId, tenantId))
                .thenReturn(Optional.of(selected));
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(
                Site.builder().id(siteId).timezone("Asia/Ho_Chi_Minh").build()));

        Optional<AssignmentService.AssignmentAvailability> result = service
                .resolveAvailableAssignmentNow(tenantId, employeeId, siteId, selectedAssignmentId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().assignment().getId()).isEqualTo(selectedAssignmentId);
        assertThat(result.orElseThrow().siteZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void rejectsAnAssignmentThatBelongsToAnotherEmployee() {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        Assignment anotherEmployeesAssignment = Assignment.builder()
                .id(assignmentId)
                .tenantId(tenantId)
                .employeeId(UUID.randomUUID())
                .siteId(siteId)
                .status("active")
                .build();

        when(assignmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(assignmentId, tenantId))
                .thenReturn(Optional.of(anotherEmployeesAssignment));

        assertThat(service.resolveAvailableAssignmentNow(
                tenantId, employeeId, siteId, assignmentId)).isEmpty();
    }

    @Test
    void safelyResolvesLegacyRequestWhenSiteHasOnlyOneRelevantAssignment() {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        LocalDate vietnamToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Assignment assignment = Assignment.builder()
                .id(assignmentId)
                .tenantId(tenantId)
                .employeeId(employeeId)
                .siteId(siteId)
                .startDate(vietnamToday)
                .status("active")
                .build();

        when(assignmentRepository.findActiveAssignmentsForEmployeeOnDate(
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(employeeId),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(assignment));
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(
                Site.builder().id(siteId).timezone("Asia/Ho_Chi_Minh").build()));

        Optional<AssignmentService.AssignmentAvailability> result = service
                .resolveAvailableAssignmentNow(tenantId, employeeId, siteId, null);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().assignment().getId()).isEqualTo(assignmentId);
    }

    @Test
    void rejectsLegacyRequestWhenMultipleAssignmentsAreAmbiguous() {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        LocalDate vietnamToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Assignment first = Assignment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).employeeId(employeeId).siteId(siteId)
                .startDate(vietnamToday).status("active").build();
        Assignment second = Assignment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).employeeId(employeeId).siteId(siteId)
                .startDate(vietnamToday).status("active").build();

        when(assignmentRepository.findActiveAssignmentsForEmployeeOnDate(
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(employeeId),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(first, second));
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(
                Site.builder().id(siteId).timezone("Asia/Ho_Chi_Minh").build()));

        assertThat(service.resolveAvailableAssignmentNow(tenantId, employeeId, siteId, null)).isEmpty();
    }
}
