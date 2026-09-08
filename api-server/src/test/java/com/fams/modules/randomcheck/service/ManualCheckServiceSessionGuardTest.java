package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.randomcheck.dto.request.ManualCheckRequest;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualCheckServiceSessionGuardTest {

    private final AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
    private final RandomCheckConfigRepository configRepository = mock(RandomCheckConfigRepository.class);
    private final ScheduledCheckRepository scheduledCheckRepository = mock(ScheduledCheckRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final PlanLimitEnforcementService limitService = mock(PlanLimitEnforcementService.class);
    private final RandomCheckDispatchService dispatchService = mock(RandomCheckDispatchService.class);
    private final FaceProfileRepository faceProfileRepository = mock(FaceProfileRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final CheckinRepository checkinRepository = mock(CheckinRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);

    private ManualCheckService service;
    private ManualCheckRequest request;
    private UUID tenantId;
    private UUID siteId;
    private UUID employeeId;
    private UUID assignmentId;
    private Shift shift;
    private RandomCheckConfig config;

    @BeforeEach
    void setUp() {
        service = new ManualCheckService(assignmentRepository, configRepository,
                scheduledCheckRepository, siteRepository, limitService, dispatchService,
                faceProfileRepository, auditLogService, checkinRepository, shiftRepository,
                employeeRepository);
        tenantId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();

        request = new ManualCheckRequest();
        request.setSiteId(siteId);
        request.setEmployeeId(employeeId);
        request.setReason("Kiểm tra hiện diện đột xuất");

        Site site = Site.builder().id(siteId).tenantId(tenantId)
                .timezone("Asia/Ho_Chi_Minh").build();
        shift = Shift.builder().id(shiftId).tenantId(tenantId).siteId(siteId)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(17, 0))
                .randomCheckPolicy("inherit").manualCheckPolicy("inherit").build();
        Assignment assignment = Assignment.builder().id(assignmentId).tenantId(tenantId)
                .siteId(siteId).employeeId(employeeId).shiftId(shiftId).status("active").build();
        CheckinRecord open = CheckinRecord.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .siteId(siteId).employeeId(employeeId).assignmentId(assignmentId).shiftId(shiftId)
                .checkInAt(OffsetDateTime.now().minusMinutes(10))
                .sessionExpiresAt(OffsetDateTime.now().plusHours(2)).build();
        config = RandomCheckConfig.builder().id(UUID.randomUUID()).tenantId(tenantId).siteId(siteId)
                .checksPerShift(2).minIntervalMinutes(30).allowedStartTime(LocalTime.of(8, 0))
                .allowedEndTime(LocalTime.of(17, 0)).windowMode("full_shift")
                .checkMode("location_only").applicableRoles("").responseWindowSeconds(300)
                .isActive(true).manualChecksAllowed(true).build();

        when(siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)).thenReturn(Optional.of(site));
        when(checkinRepository.findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                tenantId, employeeId)).thenReturn(Optional.of(open));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(configRepository.findBySite(tenantId, siteId)).thenReturn(Optional.of(config));
        when(scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndCheckIndex(
                any(), any(), any(Integer.class))).thenReturn(false);
        when(scheduledCheckRepository.save(any())).thenAnswer(invocation -> {
            ScheduledCheck check = invocation.getArgument(0);
            check.setId(UUID.randomUUID());
            return check;
        });
    }

    @Test
    void sendsImmediatelyOnlyForTheOpenSessionAndUsesSiteDate() {
        ScheduledCheck result = service.trigger(tenantId, request, UUID.randomUUID());

        assertThat(result.getAssignmentId()).isEqualTo(assignmentId);
        assertThat(result.getCheckDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        assertThat(result.getStatus()).isEqualTo("sent");
        verify(dispatchService).sendNotification(result);
    }

    @Test
    void rejectsAnEmployeeWhoIsNotCurrentlyCheckedIn() {
        when(checkinRepository.findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                tenantId, employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trigger(tenantId, request, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently checked in");
        verify(dispatchService, never()).sendNotification(any());
    }

    @Test
    void shiftCanExplicitlyDisableManualChecks() {
        shift.setManualCheckPolicy("disabled");

        assertThatThrownBy(() -> service.trigger(tenantId, request, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");
        verify(dispatchService, never()).sendNotification(any());
    }

    @Test
    void shiftCanExplicitlyEnableManualChecksWhenParentDisallowsThem() {
        config.setManualChecksAllowed(false);
        shift.setManualCheckPolicy("enabled");

        ScheduledCheck result = service.trigger(tenantId, request, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo("sent");
        verify(dispatchService).sendNotification(result);
    }

    @Test
    void candidateListContainsOnlyAnUnexpiredOpenSessionAllowedByPolicy() {
        CheckinRecord open = CheckinRecord.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .siteId(siteId).employeeId(employeeId).assignmentId(assignmentId)
                .shiftId(shift.getId()).checkInAt(OffsetDateTime.now().minusMinutes(15))
                .sessionExpiresAt(OffsetDateTime.now().plusHours(1)).build();
        Employee employee = Employee.builder().id(employeeId).tenantId(tenantId)
                .firstName("Duy Anh").lastName("Nguyễn Bá").employeeCode("NV001")
                .status("active").build();
        when(checkinRepository.findUnexpiredOpenSessionsBySite(
                org.mockito.ArgumentMatchers.eq(tenantId), org.mockito.ArgumentMatchers.eq(siteId),
                any(OffsetDateTime.class))).thenReturn(List.of(open));
        when(employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(tenantId), any())).thenReturn(List.of(employee));

        var candidates = service.listEligibleCandidates(tenantId, siteId);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.getEmployeeId()).isEqualTo(employeeId);
            assertThat(candidate.getEmployeeName()).isEqualTo("Nguyễn Bá Duy Anh");
            assertThat(candidate.getShiftId()).isEqualTo(shift.getId());
        });
    }

    @Test
    void candidateListHidesAnOpenSessionWhenShiftDisablesManualChecks() {
        shift.setManualCheckPolicy("disabled");
        CheckinRecord open = CheckinRecord.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .siteId(siteId).employeeId(employeeId).assignmentId(assignmentId)
                .shiftId(shift.getId()).checkInAt(OffsetDateTime.now().minusMinutes(15)).build();
        when(checkinRepository.findUnexpiredOpenSessionsBySite(
                org.mockito.ArgumentMatchers.eq(tenantId), org.mockito.ArgumentMatchers.eq(siteId),
                any(OffsetDateTime.class))).thenReturn(List.of(open));

        assertThat(service.listEligibleCandidates(tenantId, siteId)).isEmpty();
        verify(employeeRepository, never())
                .findAllByTenantIdAndIdInAndDeletedAtIsNull(any(), any());
    }
}
