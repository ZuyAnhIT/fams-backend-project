package com.fams.modules.checkin.service;

import com.fams.modules.checkin.entity.CheckinRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinSessionCutoffTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void nonOvertimeShiftKeepsOnlyTheConfiguredCheckoutGrace() {
        CheckinRecord record = CheckinRecord.builder()
                .checkInAt(OffsetDateTime.of(2026, 9, 4, 13, 10, 0, 0, ZoneOffset.UTC))
                .shiftEndTime(LocalTime.of(20, 55))
                .shiftAllowOvernight(false)
                .shiftAllowOvertime(false)
                .shiftLateCheckoutMinutes(3)
                .build();

        assertThat(CheckinService.calculateSessionCutoff(record, VIETNAM))
                .isEqualTo(OffsetDateTime.parse("2026-09-04T20:58:00+07:00"));
    }

    @Test
    void overtimeShiftRemainsOpenUntilItsConfiguredOtCheckoutLimit() {
        CheckinRecord record = CheckinRecord.builder()
                .checkInAt(OffsetDateTime.of(2026, 9, 4, 13, 10, 0, 0, ZoneOffset.UTC))
                .shiftEndTime(LocalTime.of(20, 55))
                .shiftAllowOvernight(false)
                .shiftAllowOvertime(true)
                .shiftLateCheckoutMinutes(120)
                .build();

        assertThat(CheckinService.calculateSessionCutoff(record, VIETNAM))
                .isEqualTo(OffsetDateTime.parse("2026-09-04T22:55:00+07:00"));
    }

    @Test
    void overnightCheckinAfterMidnightUsesPreviousShiftOccurrence() {
        CheckinRecord record = CheckinRecord.builder()
                .checkInAt(OffsetDateTime.of(2026, 9, 4, 18, 30, 0, 0, ZoneOffset.UTC))
                .shiftEndTime(LocalTime.of(6, 0))
                .shiftAllowOvernight(true)
                .shiftLateCheckoutMinutes(15)
                .build();

        assertThat(CheckinService.calculateSessionCutoff(record, VIETNAM))
                .isEqualTo(OffsetDateTime.parse("2026-09-05T06:15:00+07:00"));
    }

    @Test
    void shiftlessAssignmentExpiresAtNextSiteLocalMidnight() {
        CheckinRecord record = CheckinRecord.builder()
                .checkInAt(OffsetDateTime.of(2026, 9, 4, 16, 30, 0, 0, ZoneOffset.UTC))
                .build();

        assertThat(CheckinService.calculateSessionCutoff(record, VIETNAM))
                .isEqualTo(OffsetDateTime.parse("2026-09-05T00:00:00+07:00"));
    }
}
