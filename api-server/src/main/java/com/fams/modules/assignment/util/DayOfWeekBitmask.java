package com.fams.modules.assignment.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Issue #14 (docs/issues/ISSUES.md): converts between the API's {@code Set<DayOfWeek>}
 * representation and the single-column bitmask stored on {@code assignments.days_of_week}.
 * Bit N (0-indexed) corresponds to ISO day-of-week N+1 (bit0=Monday..bit6=Sunday).
 * {@code null} means "every day" throughout — the pre-existing, backward-compatible default.
 */
public final class DayOfWeekBitmask {

    private DayOfWeekBitmask() {
    }

    public static int bitFor(DayOfWeek dayOfWeek) {
        return 1 << (dayOfWeek.getValue() - 1);
    }

    public static int bitForDate(LocalDate date) {
        return bitFor(date.getDayOfWeek());
    }

    /** Returns null if {@code days} is null (meaning "every day, unrestricted"). */
    public static Short toBitmask(Set<DayOfWeek> days) {
        if (days == null) {
            return null;
        }
        int mask = 0;
        for (DayOfWeek day : days) {
            mask |= bitFor(day);
        }
        return (short) mask;
    }

    /** Returns null if {@code mask} is null (meaning "every day, unrestricted"). */
    public static Set<DayOfWeek> fromBitmask(Short mask) {
        if (mask == null) {
            return null;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((mask & bitFor(day)) != 0) {
                days.add(day);
            }
        }
        return days;
    }
}
