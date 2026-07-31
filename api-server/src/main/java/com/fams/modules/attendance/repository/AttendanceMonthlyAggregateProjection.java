package com.fams.modules.attendance.repository;

import java.util.UUID;

/**
 * Row shape for {@link AttendanceSummaryRepository#aggregateMonthly}. Spring Data maps native
 * query column aliases to these getters via relaxed (underscore-insensitive) binding — no
 * explicit `AS` aliasing needed beyond matching column/computed-column names.
 */
public interface AttendanceMonthlyAggregateProjection {
    UUID getEmployeeId();
    UUID getSiteId();
    Long getPresentDays();
    Long getTotalWorkMinutes();
    Long getLateDays();
    Long getTotalLateMinutes();
    Long getEarlyLeaveDays();
    Long getTotalEarlyLeaveMinutes();
    Long getTotalOtMinutes();
    Long getMissingCheckoutDays();
    Long getDaysWithPendingReview();
    Long getDaysWithRejectedSession();
}
