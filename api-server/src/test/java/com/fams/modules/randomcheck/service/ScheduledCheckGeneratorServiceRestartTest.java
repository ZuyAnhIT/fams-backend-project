package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledCheckGeneratorServiceRestartTest {

    private final AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
    private final RandomCheckConfigRepository configRepository = mock(RandomCheckConfigRepository.class);
    private final ScheduledCheckRepository scheduledCheckRepository = mock(ScheduledCheckRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final RandomCheckDispatchQueue dispatchQueue = mock(RandomCheckDispatchQueue.class);
    private final PlanLimitEnforcementService planLimitService = mock(PlanLimitEnforcementService.class);
    private final FaceProfileRepository faceProfileRepository = mock(FaceProfileRepository.class);
    private final CheckinRepository checkinRepository = mock(CheckinRepository.class);

    private ScheduledCheckGeneratorService service;
    private Assignment assignment;
    private UUID tenantId;
    private UUID siteId;
    private UUID employeeId;
    private Shift shift;
    private RandomCheckConfig config;
    private final List<ScheduledCheck> savedChecks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ScheduledCheckGeneratorService(
                assignmentRepository, configRepository, scheduledCheckRepository,
                shiftRepository, siteRepository, dispatchQueue, planLimitService,
                faceProfileRepository, checkinRepository);

        tenantId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();

        assignment = Assignment.builder()
                .id(assignmentId)
                .tenantId(tenantId)
                .siteId(siteId)
                .employeeId(employeeId)
                .shiftId(shiftId)
                .role("worker")
                .status("active")
                .startDate(LocalDate.of(2026, 8, 1))
                .build();

        Site site = Site.builder()
                .id(siteId)
                .tenantId(tenantId)
                .timezone("Asia/Ho_Chi_Minh")
                .status("active")
                .build();
        shift = Shift.builder()
                .id(shiftId)
                .tenantId(tenantId)
                .siteId(siteId)
                .startTime(LocalTime.of(20, 40))
                .endTime(LocalTime.of(20, 55))
                .randomCheckPolicy("inherit")
                .manualCheckPolicy("inherit")
                .build();
        config = RandomCheckConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .siteId(siteId)
                .checksPerShift(3)
                .minIntervalMinutes(4)
                .allowedStartTime(LocalTime.of(20, 40))
                .allowedEndTime(LocalTime.of(20, 55))
                .windowMode("full_shift")
                .checkMode("location_face_liveness")
                .applicableRoles("")
                .responseWindowSeconds(120)
                .isActive(true)
                .manualChecksAllowed(true)
                .build();

        when(assignmentRepository.findAllActiveAssignmentsWithShiftForDate(any(), anyInt()))
                .thenAnswer(invocation -> LocalDate.of(2026, 9, 4).equals(invocation.getArgument(0))
                        ? List.of(assignment) : List.of());
        when(siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId))
                .thenReturn(Optional.of(site));
        when(configRepository.findBySite(tenantId, siteId)).thenReturn(Optional.of(config));
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndCheckIndexGreaterThanAndDeletedAtIsNull(
                assignmentId, LocalDate.of(2026, 9, 4), 0)).thenReturn(false);
        when(planLimitService.getRemainingRandomChecks(tenantId)).thenReturn(Integer.MAX_VALUE);
        when(faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId))
                .thenReturn(Optional.of(FaceProfile.builder().status("enrolled").build()));
        when(checkinRepository
                .findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                        tenantId, employeeId)).thenReturn(Optional.empty());
        when(scheduledCheckRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ScheduledCheck> checks = (List<ScheduledCheck>) invocation.getArgument(0);
            checks.stream()
                    .filter(check -> check.getId() == null)
                    .forEach(check -> check.setId(UUID.randomUUID()));
            savedChecks.addAll(checks);
            return checks;
        });
    }

    @Test
    void restartDuringWindowCreatesOnlyFutureChecksInSiteTimezone() {
        OffsetDateTime restartedAt = OffsetDateTime.of(
                2026, 9, 4, 13, 42, 20, 0, ZoneOffset.UTC); // 20:42 Vietnam

        int created = service.ensureSchedulesForCurrentLocalDates(restartedAt);

        assertThat(created).isEqualTo(3);
        assertThat(savedChecks).hasSize(3);
        assertThat(savedChecks)
                .allSatisfy(check -> {
                    assertThat(check.getScheduledAt()).isAfter(restartedAt);
                    assertThat(check.getScheduledAt().atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                            .toLocalTime()).isBetween(LocalTime.of(20, 43), LocalTime.of(20, 55));
                });
        assertThat(savedChecks)
                .extracting(ScheduledCheck::getScheduledAt)
                .isSorted();
        savedChecks.forEach(check -> verify(dispatchQueue)
                .enqueue(eq(check.getId()), eq(check.getScheduledAt())));
    }

    @Test
    void restartAfterWindowDoesNotSendExpiredChecksInABurst() {
        OffsetDateTime restartedAt = OffsetDateTime.of(
                2026, 9, 4, 13, 56, 0, 0, ZoneOffset.UTC); // 20:56 Vietnam

        int created = service.ensureSchedulesForCurrentLocalDates(restartedAt);

        assertThat(created).isZero();
        assertThat(savedChecks).isEmpty();
        verify(scheduledCheckRepository, never()).saveAll(any());
        verify(dispatchQueue, never()).enqueue(any(), any());
    }

    @Test
    void fullShiftModeUsesASecondShiftEvenWhenLegacyClockWindowDoesNotOverlap() {
        shift.setStartTime(LocalTime.of(22, 20));
        shift.setEndTime(LocalTime.of(22, 35));
        config.setAllowedStartTime(LocalTime.of(8, 0));
        config.setAllowedEndTime(LocalTime.of(15, 0));

        int created = service.ensureSchedulesForCurrentLocalDates(OffsetDateTime.of(
                2026, 9, 4, 13, 0, 0, 0, ZoneOffset.UTC)); // 20:00 Vietnam

        assertThat(created).isEqualTo(3);
        assertThat(savedChecks).allSatisfy(check -> assertThat(
                check.getScheduledAt().atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalTime())
                .isBetween(LocalTime.of(22, 20), LocalTime.of(22, 35)));
    }

    @Test
    void explicitlyDisabledShiftCreatesNoAutomaticChecks() {
        shift.setRandomCheckPolicy("disabled");

        int created = service.ensureSchedulesForCurrentLocalDates(OffsetDateTime.of(
                2026, 9, 4, 13, 42, 0, 0, ZoneOffset.UTC));

        assertThat(created).isZero();
        verify(scheduledCheckRepository, never()).saveAll(any());
    }

    @Test
    void customWindowTooShortNeverLeaksACheckPastItsEnd() {
        config.setWindowMode("custom_window");
        config.setAllowedStartTime(LocalTime.of(20, 50));
        config.setAllowedEndTime(LocalTime.of(20, 55));

        int created = service.generateForAssignment(assignment, LocalDate.of(2026, 9, 4));

        assertThat(created).isZero();
        verify(scheduledCheckRepository, never()).saveAll(any());
    }

    @Test
    void overnightShiftProducesAnAbsoluteWindowAcrossMidnight() {
        shift.setStartTime(LocalTime.of(22, 0));
        shift.setEndTime(LocalTime.of(6, 0));
        shift.setAllowOvernight(true);

        ScheduledCheckGeneratorService.EffectiveWindow window = service.resolveEffectiveWindow(
                config, shift, LocalDate.of(2026, 9, 4), ZoneId.of("Asia/Ho_Chi_Minh")).orElseThrow();

        assertThat(window.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(window.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(java.time.Duration.between(window.start(), window.end()).toHours()).isEqualTo(8);
    }

    @Test
    void impossibleWindowIsRejectedInsteadOfClampingAndOverflowing() {
        OffsetDateTime start = OffsetDateTime.of(2026, 9, 4, 8, 0, 0, 0, ZoneOffset.UTC);
        assertThatThrownBy(() -> service.generateCheckTimes(start, start.plusMinutes(5), 3, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void lateCheckinReplenishesChecksCancelledBeforeTheSessionOpened() {
        OffsetDateTime checkedInAt = OffsetDateTime.of(
                2026, 9, 4, 13, 42, 20, 0, ZoneOffset.UTC); // 20:42 Vietnam
        List<ScheduledCheck> cancelled = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            cancelled.add(ScheduledCheck.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignment.getId())
                    .checkDate(LocalDate.of(2026, 9, 4))
                    .checkIndex(index)
                    .scheduledAt(checkedInAt.minusMinutes(index))
                    .status("cancelled")
                    .cancelledReason("employee_not_in_active_session")
                    .build());
        }
        when(checkinRepository
                .findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                        tenantId, employeeId)).thenReturn(Optional.of(CheckinRecord.builder()
                            .assignmentId(assignment.getId())
                            .sessionExpiresAt(checkedInAt.plusHours(1))
                            .build()));
        when(scheduledCheckRepository.findByAssignmentAndDate(
                assignment.getId(), LocalDate.of(2026, 9, 4))).thenReturn(cancelled);
        when(scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndCheckIndexGreaterThanAndDeletedAtIsNull(
                assignment.getId(), LocalDate.of(2026, 9, 4), 0)).thenReturn(true);

        int created = service.ensureSchedulesForCurrentLocalDates(checkedInAt);

        assertThat(created).isEqualTo(3);
        assertThat(savedChecks).hasSize(3);
        assertThat(savedChecks).extracting(ScheduledCheck::getCheckIndex)
                .containsExactly(4, 5, 6);
        assertThat(savedChecks).allSatisfy(check -> assertThat(check.getScheduledAt()).isAfter(checkedInAt));
    }

    @Test
    void shiftEditKeepsRespondedChecksAndReplacesOnlyPendingSlots() {
        OffsetDateTime editedAt = OffsetDateTime.of(
                2026, 9, 4, 13, 42, 20, 0, ZoneOffset.UTC); // 20:42 Vietnam
        ScheduledCheck responded = ScheduledCheck.builder()
                .id(UUID.randomUUID()).assignmentId(assignment.getId())
                .checkDate(LocalDate.of(2026, 9, 4)).checkIndex(1)
                .scheduledAt(editedAt.minusMinutes(2)).status("responded").build();
        ScheduledCheck pendingOne = ScheduledCheck.builder()
                .id(UUID.randomUUID()).assignmentId(assignment.getId())
                .checkDate(LocalDate.of(2026, 9, 4)).checkIndex(2)
                .scheduledAt(editedAt.plusMinutes(1)).status("pending").build();
        ScheduledCheck pendingTwo = ScheduledCheck.builder()
                .id(UUID.randomUUID()).assignmentId(assignment.getId())
                .checkDate(LocalDate.of(2026, 9, 4)).checkIndex(3)
                .scheduledAt(editedAt.plusMinutes(5)).status("pending").build();
        when(assignmentRepository.findByTenantIdAndShiftIdAndStatusAndDeletedAtIsNull(
                tenantId, assignment.getShiftId(), "active")).thenReturn(List.of(assignment));
        when(scheduledCheckRepository.findByAssignmentAndDate(
                assignment.getId(), LocalDate.of(2026, 9, 4)))
                .thenReturn(List.of(responded, pendingOne, pendingTwo));

        int created = service.rescheduleCurrentDateForShift(
                tenantId, assignment.getShiftId(), editedAt);

        assertThat(created).isEqualTo(2);
        assertThat(responded.getStatus()).isEqualTo("responded");
        assertThat(pendingOne.getStatus()).isEqualTo("cancelled");
        assertThat(pendingTwo.getStatus()).isEqualTo("cancelled");
        assertThat(savedChecks.stream().filter(check -> "pending".equals(check.getStatus())).toList())
                .extracting(ScheduledCheck::getCheckIndex)
                .containsExactly(4, 5);
        verify(dispatchQueue).cancel(pendingOne.getId());
        verify(dispatchQueue).cancel(pendingTwo.getId());
    }
}
