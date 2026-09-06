package com.fams.modules.assignment.util;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.shift.entity.Shift;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentLifecycleResolverTest {

    private static final java.time.ZoneId VIETNAM = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void finalShiftIsCompletedAtItsVietnamEndTime() {
        Assignment assignment = assignment("active", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5));
        Shift shift = Shift.builder()
                .startTime(LocalTime.of(21, 25))
                .endTime(LocalTime.of(21, 35))
                .allowOvernight(false)
                .build();

        assertThat(AssignmentLifecycleResolver.resolve(
                assignment, shift, VIETNAM, Instant.parse("2026-09-05T14:34:59Z")))
                .isEqualTo(AssignmentLifecycleResolver.EFFECTIVE);
        assertThat(AssignmentLifecycleResolver.resolve(
                assignment, shift, VIETNAM, Instant.parse("2026-09-05T14:35:00Z")))
                .isEqualTo(AssignmentLifecycleResolver.COMPLETED);
    }

    @Test
    void overnightFinalShiftEndsOnFollowingVietnamDate() {
        Assignment assignment = assignment("active", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5));
        Shift shift = Shift.builder()
                .startTime(LocalTime.of(22, 0))
                .endTime(LocalTime.of(6, 0))
                .allowOvernight(true)
                .build();

        assertThat(AssignmentLifecycleResolver.resolve(
                assignment, shift, VIETNAM, Instant.parse("2026-09-05T22:59:59Z")))
                .isEqualTo(AssignmentLifecycleResolver.EFFECTIVE);
        assertThat(AssignmentLifecycleResolver.resolve(
                assignment, shift, VIETNAM, Instant.parse("2026-09-05T23:00:00Z")))
                .isEqualTo(AssignmentLifecycleResolver.COMPLETED);
    }

    @Test
    void cancelledRecordNeverPretendsEmployeeIsWorking() {
        Assignment assignment = assignment("cancelled", LocalDate.of(2026, 9, 5), null);

        assertThat(AssignmentLifecycleResolver.resolve(
                assignment, null, VIETNAM, Instant.parse("2026-09-05T03:00:00Z")))
                .isEqualTo(AssignmentLifecycleResolver.CANCELLED);
    }

    private Assignment assignment(String status, LocalDate start, LocalDate end) {
        return Assignment.builder()
                .status(status)
                .startDate(start)
                .endDate(end)
                .role("worker")
                .build();
    }
}
