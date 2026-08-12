package com.fams.modules.checkin.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineSyncServiceTimestampTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-11T12:00:00Z");

    @Test
    void acceptsTimestampAtConfiguredBoundaries() {
        assertThat(OfflineSyncService.validateOfflineTimestamp(
                NOW.minusHours(24), NOW, 24, 5)).isNull();
        assertThat(OfflineSyncService.validateOfflineTimestamp(
                NOW.plusMinutes(5), NOW, 24, 5)).isNull();
    }

    @Test
    void rejectsTimestampOlderThanRecoveryWindow() {
        assertThat(OfflineSyncService.validateOfflineTimestamp(
                NOW.minusHours(24).minusNanos(1), NOW, 24, 5))
                .contains("older than the allowed 24 hour");
    }

    @Test
    void rejectsTimestampTooFarInFuture() {
        assertThat(OfflineSyncService.validateOfflineTimestamp(
                NOW.plusMinutes(5).plusNanos(1), NOW, 24, 5))
                .contains("more than 5 minutes in the future");
    }
}
