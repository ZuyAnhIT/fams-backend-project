package com.fams.modules.randomcheck.service;

import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RandomCheckDispatchServiceSessionGuardTest {

    private final ScheduledCheckRepository checkRepository = mock(ScheduledCheckRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final CheckinRepository checkinRepository = mock(CheckinRepository.class);
    private RandomCheckDispatchService service;
    private ScheduledCheck check;

    @BeforeEach
    void setUp() {
        service = new RandomCheckDispatchService(
                checkRepository, employeeRepository, notificationService, checkinRepository);
        check = ScheduledCheck.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID()).employeeId(UUID.randomUUID())
                .siteId(UUID.randomUUID()).assignmentId(UUID.randomUUID())
                .status("pending").configSnapshot("{\"responseWindowSeconds\":120}")
                .expiresAt(OffsetDateTime.now().plusMinutes(2)).build();
        when(checkRepository.findById(check.getId())).thenReturn(Optional.of(check));
    }

    @Test
    void cancelsInsteadOfNotifyingOrCreatingNoResponseWhenEmployeeIsAbsent() {
        when(checkinRepository.findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                check.getTenantId(), check.getEmployeeId())).thenReturn(Optional.empty());

        service.dispatch(check.getId());

        assertThat(check.getStatus()).isEqualTo("cancelled");
        assertThat(check.getCancelledReason()).isEqualTo("employee_not_in_active_session");
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendsWhenTheScheduledAssignmentStillHasAMatchingOpenSession() {
        CheckinRecord open = CheckinRecord.builder()
                .tenantId(check.getTenantId()).employeeId(check.getEmployeeId())
                .siteId(check.getSiteId()).assignmentId(check.getAssignmentId())
                .sessionExpiresAt(OffsetDateTime.now().plusHours(1)).build();
        Employee employee = Employee.builder().id(check.getEmployeeId()).tenantId(check.getTenantId())
                .userId(UUID.randomUUID()).build();
        when(checkinRepository.findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                check.getTenantId(), check.getEmployeeId())).thenReturn(Optional.of(open));
        when(employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(check.getEmployeeId(), check.getTenantId()))
                .thenReturn(Optional.of(employee));

        service.dispatch(check.getId());

        assertThat(check.getStatus()).isEqualTo("sent");
        assertThat(check.getSentAt()).isNotNull();
        verify(notificationService).createNotification(any(), any(), any(), any(), any(), any());
    }
}
