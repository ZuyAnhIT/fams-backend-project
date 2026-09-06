package com.fams.modules.assignment.util;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.shift.entity.Shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Derives the time-aware lifecycle shown to users without changing the persisted assignment
 * status. The database status answers whether HR cancelled the record; it must not be rendered
 * as "Đang làm việc", which would incorrectly imply an open attendance session.
 */
public final class AssignmentLifecycleResolver {

    public static final String UPCOMING = "upcoming";
    public static final String EFFECTIVE = "effective";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    private AssignmentLifecycleResolver() {
    }

    public static String resolve(Assignment assignment, Shift shift, ZoneId zone, Instant now) {
        if (!"active".equals(assignment.getStatus())) return CANCELLED;

        Set<java.time.DayOfWeek> days = DayOfWeekBitmask.fromBitmask(assignment.getDaysOfWeek());
        LocalDate firstDate = nextScheduledDate(assignment.getStartDate(), days);
        LocalDate endDate = assignment.getEndDate();

        if (endDate != null && firstDate.isAfter(endDate)) {
            return now.isBefore(assignment.getStartDate().atStartOfDay(zone).toInstant())
                    ? UPCOMING : COMPLETED;
        }

        Instant firstStart = occurrenceStart(firstDate, shift, zone);
        if (now.isBefore(firstStart)) return UPCOMING;
        if (endDate == null) return EFFECTIVE;

        LocalDate lastDate = previousScheduledDate(endDate, days);
        if (lastDate.isBefore(assignment.getStartDate())) return COMPLETED;

        Instant finalEnd = occurrenceEnd(lastDate, shift, zone);
        return now.isBefore(finalEnd) ? EFFECTIVE : COMPLETED;
    }

    private static LocalDate nextScheduledDate(LocalDate date, Set<java.time.DayOfWeek> days) {
        if (days == null || days.isEmpty()) return date;
        LocalDate candidate = date;
        for (int i = 0; i < 7; i++, candidate = candidate.plusDays(1)) {
            if (days.contains(candidate.getDayOfWeek())) return candidate;
        }
        return date;
    }

    private static LocalDate previousScheduledDate(LocalDate date, Set<java.time.DayOfWeek> days) {
        if (days == null || days.isEmpty()) return date;
        LocalDate candidate = date;
        for (int i = 0; i < 7; i++, candidate = candidate.minusDays(1)) {
            if (days.contains(candidate.getDayOfWeek())) return candidate;
        }
        return date;
    }

    private static Instant occurrenceStart(LocalDate date, Shift shift, ZoneId zone) {
        return shift == null
                ? date.atStartOfDay(zone).toInstant()
                : ZonedDateTime.of(date, shift.getStartTime(), zone).toInstant();
    }

    private static Instant occurrenceEnd(LocalDate date, Shift shift, ZoneId zone) {
        if (shift == null) return date.plusDays(1).atStartOfDay(zone).toInstant();
        LocalDate shiftEndDate = shift.isAllowOvernight() ? date.plusDays(1) : date;
        return ZonedDateTime.of(shiftEndDate, shift.getEndTime(), zone).toInstant();
    }
}
