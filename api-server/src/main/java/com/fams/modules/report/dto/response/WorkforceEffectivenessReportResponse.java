package com.fams.modules.report.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WorkforceEffectivenessReportResponse(
        LocalDate from,
        LocalDate to,
        WorkforceKpis kpis,
        Comparison comparison,
        List<DailyTrend> dailyTrend,
        List<SiteBreakdown> bySite,
        List<WeekdayShortage> shortageByWeekday) {

    public record WorkforceKpis(long assignedOccurrences, long presentOccurrences,
                                long absentOccurrences, double attendanceRate,
                                double absenceRate, double lateRate, double earlyLeaveRate,
                                double missingCheckoutRate, long totalWorkMinutes,
                                long totalOtMinutes, long averageWorkMinutesPerEmployee) {}

    public record Comparison(double attendanceRateChange, double lateRateChange,
                             double absenceRateChange, double workMinutesChange,
                             double otMinutesChange) {}

    public record DailyTrend(LocalDate date, long assigned, long present, long absent,
                             long late, long earlyLeave, long missingCheckout,
                             long workMinutes, long otMinutes) {}

    public record SiteBreakdown(UUID siteId, String siteName, long assigned, long present,
                                long absent, double attendanceRate, long workMinutes,
                                long otMinutes) {}

    public record WeekdayShortage(int isoDayOfWeek, String weekday, long assigned,
                                  long absent, double absenceRate) {}
}
