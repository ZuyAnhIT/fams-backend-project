package com.fams.modules.report.service;

import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.report.dto.response.PlatformCustomerHealthReportResponse;
import com.fams.modules.report.dto.response.PlatformRevenueReportResponse;
import com.fams.modules.report.dto.response.RiskComplianceReportResponse;
import com.fams.modules.report.dto.response.WorkforceEffectivenessReportResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AnalyticsReportService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final UUID EMPTY_SCOPE_SENTINEL = new UUID(0, 0);

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;

    public AnalyticsReportService(NamedParameterJdbcTemplate jdbc,
                                  UserRoleRepository userRoleRepository,
                                  SiteScopeService siteScopeService) {
        this.jdbc = jdbc;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
    }

    public PlatformRevenueReportResponse platformRevenue(LocalDate from, LocalDate to, int expiryDays,
            UUID tenantId, UUID planId, String subscriptionStatus) {
        String normalizedStatus = normalizeSubscriptionStatus(subscriptionStatus);
        MapSqlParameterSource p = periodParams(from, to)
                .addValue("expiryDays", Math.max(1, Math.min(expiryDays, 90)))
                .addValue("platformTenantId", tenantId).addValue("platformPlanId", planId)
                .addValue("platformSubscriptionStatus", normalizedStatus);
        PlatformFilter filter = new PlatformFilter(tenantId, planId, normalizedStatus);
        String bf = billingFilter("b", filter);
        String sf = subscriptionFilter("ts", filter);
        String tf = tenantFilter("t", filter);

        Map<String, Object> totals = jdbc.queryForMap(("""
                SELECT COALESCE(SUM(b.amount_paid) FILTER (WHERE b.status='PAID'
                           AND b.paid_at>=:fromTs AND b.paid_at<:toTs), 0) collected,
                       COUNT(DISTINCT b.tenant_id) FILTER (WHERE b.status='PAID'
                           AND b.paid_at>=:fromTs AND b.paid_at<:toTs) paid_tenants,
                       COUNT(*) FILTER (WHERE b.status='PAID' AND b.created_at>=:fromTs AND b.created_at<:toTs) paid_orders,
                       COUNT(*) FILTER (WHERE b.status IN ('PAID','FAILED','EXPIRED','CANCELLED')
                           AND b.created_at>=:fromTs AND b.created_at<:toTs) terminal_orders
                FROM billing_orders b WHERE 1=1
                """) + bf, p);
        long collected = longValue(totals.get("collected"));
        long paidTenants = longValue(totals.get("paid_tenants"));
        long paidOrders = longValue(totals.get("paid_orders"));
        long terminalOrders = longValue(totals.get("terminal_orders"));
        long currentMrr = queryLong(("""
                SELECT COALESCE(SUM(CASE WHEN ts.billing_cycle='YEARLY'
                         THEN p.price_yearly / 12 ELSE p.price_monthly END), 0)
                FROM tenant_subscriptions ts JOIN plans p ON p.id=ts.plan_id
                WHERE ts.status='ACTIVE' AND (ts.expires_at IS NULL OR ts.expires_at > now())
                  AND p.deleted_at IS NULL
                """) + sf, p);
        long cohortTenants = queryLong("SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL AND t.created_at>=:fromTs AND t.created_at<:toTs" + tf, p);
        long convertedTenants = queryLong("SELECT COUNT(DISTINCT t.id) FROM tenants t JOIN billing_orders b ON b.tenant_id=t.id AND b.status='PAID' WHERE t.deleted_at IS NULL AND t.created_at>=:fromTs AND t.created_at<:toTs" + tf + bf, p);
        long renewedTenants = queryLong("SELECT COUNT(*) FROM (SELECT b.tenant_id FROM billing_orders b WHERE b.status='PAID'" + bf + " GROUP BY b.tenant_id HAVING COUNT(*)>=2) renewed", p);
        long allPaidTenants = queryLong("SELECT COUNT(DISTINCT b.tenant_id) FROM billing_orders b WHERE b.status='PAID'" + bf, p);
        Map<String, Long> subscriptionCounts = groupedCounts("SELECT UPPER(ts.status) label,COUNT(*) amount FROM tenant_subscriptions ts WHERE 1=1" + sf + " GROUP BY UPPER(ts.status)", p, List.of("TRIAL", "ACTIVE", "EXPIRED", "CANCELLED"));
        Map<String, Long> paymentStatus = groupedCounts("SELECT b.status label,COUNT(*) amount FROM billing_orders b WHERE b.created_at>=:fromTs AND b.created_at<:toTs" + bf + " GROUP BY b.status", p, List.of("PAID", "PENDING", "PROCESSING", "FAILED", "EXPIRED", "CANCELLED", "UNDERPAID"));
        long endedSubscriptions = subscriptionCounts.getOrDefault("EXPIRED", 0L) + subscriptionCounts.getOrDefault("CANCELLED", 0L);
        long nonTrialSubscriptions = subscriptionCounts.getOrDefault("ACTIVE", 0L) + endedSubscriptions;

        String trendSql = """
                WITH months AS (
                  SELECT generate_series(date_trunc('month',CAST(:fromTs AS timestamptz)),
                    date_trunc('month',CAST(:toTs AS timestamptz)-interval '1 day'),interval '1 month') month_start)
                SELECT to_char(m.month_start,'YYYY-MM') period,
                  COALESCE((SELECT SUM(b.amount_paid) FROM billing_orders b WHERE b.status='PAID'
                    AND b.paid_at>=m.month_start AND b.paid_at<m.month_start+interval '1 month'%s),0) collected,
                  COALESCE((SELECT SUM(CASE WHEN ts.billing_cycle='YEARLY' THEN pl.price_yearly/12 ELSE pl.price_monthly END)
                    FROM tenant_subscriptions ts JOIN plans pl ON pl.id=ts.plan_id
                    WHERE ts.started_at<m.month_start+interval '1 month'
                      AND (ts.expires_at IS NULL OR ts.expires_at>=m.month_start) AND ts.status='ACTIVE'%s),0) mrr
                FROM months m ORDER BY m.month_start
                """.formatted(bf, sf);
        List<PlatformRevenueReportResponse.RevenueTrendPoint> trend = jdbc.query(trendSql, p,
                (rs, row) -> new PlatformRevenueReportResponse.RevenueTrendPoint(
                        rs.getString("period"), rs.getLong("collected"), rs.getLong("mrr")));

        PlatformFilter withoutPlan = new PlatformFilter(tenantId, null, normalizedStatus);
        String planBf = billingFilter("b", withoutPlan);
        String planSf = subscriptionFilter("ts", withoutPlan);
        String byPlanSql = """
                SELECT p.id,p.display_name,
                  COALESCE(SUM(b.amount_paid) FILTER (WHERE b.status='PAID' AND b.paid_at>=:fromTs AND b.paid_at<:toTs),0) revenue,
                  COUNT(b.id) FILTER (WHERE b.status='PAID' AND b.paid_at>=:fromTs AND b.paid_at<:toTs) paid_orders,
                  (SELECT COUNT(*) FROM tenant_subscriptions ts WHERE ts.plan_id=p.id AND ts.status='ACTIVE'
                    AND (ts.expires_at IS NULL OR ts.expires_at>now())%s) active_subscriptions
                FROM plans p LEFT JOIN billing_orders b ON b.plan_id=p.id%s
                WHERE p.deleted_at IS NULL%s GROUP BY p.id,p.display_name ORDER BY revenue DESC
                """.formatted(planSf, planBf, planId == null ? "" : " AND p.id=:platformPlanId");
        List<PlatformRevenueReportResponse.PlanRevenue> byPlan = jdbc.query(byPlanSql, p, (rs, row) -> {
            long revenue = rs.getLong("revenue");
            return new PlatformRevenueReportResponse.PlanRevenue(rs.getObject("id", UUID.class),
                    rs.getString("display_name"), revenue, rs.getLong("paid_orders"),
                    rs.getLong("active_subscriptions"), percent(revenue, collected));
        });
        String expiringSql = ("""
                SELECT t.id tenant_id,t.name tenant_name,p.display_name plan_name,ts.billing_cycle,ts.expires_at,
                  GREATEST(0,CEIL(EXTRACT(EPOCH FROM (ts.expires_at-now()))/86400))::bigint days_remaining
                FROM tenant_subscriptions ts JOIN tenants t ON t.id=ts.tenant_id AND t.deleted_at IS NULL
                JOIN plans p ON p.id=ts.plan_id WHERE ts.status='ACTIVE' AND ts.expires_at>=now()
                  AND ts.expires_at<now()+(:expiryDays || ' days')::interval
                """) + sf + " ORDER BY ts.expires_at";
        List<PlatformRevenueReportResponse.ExpiringSubscription> expiring = jdbc.query(expiringSql, p,
                (rs, row) -> new PlatformRevenueReportResponse.ExpiringSubscription(
                        rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"),
                        rs.getString("plan_name"), rs.getString("billing_cycle"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getLong("days_remaining")));
        var kpis = new PlatformRevenueReportResponse.RevenueKpis(collected, currentMrr,
                percent(convertedTenants, cohortTenants), percent(renewedTenants, allPaidTenants),
                percent(endedSubscriptions, nonTrialSubscriptions),
                paidTenants == 0 ? 0 : collected / paidTenants, percent(paidOrders, terminalOrders));
        var funnel = new PlatformRevenueReportResponse.Funnel(cohortTenants, convertedTenants,
                queryLong("SELECT COUNT(*) FROM tenant_subscriptions ts WHERE ts.status='ACTIVE'" + sf, p),
                renewedTenants);
        return new PlatformRevenueReportResponse(from, to, kpis, subscriptionCounts, paymentStatus,
                trend, byPlan, funnel, expiring);
    }

    public PlatformCustomerHealthReportResponse platformCustomerHealth(LocalDate from, LocalDate to,
            UUID tenantId, UUID planId, String subscriptionStatus) {
        String normalizedStatus = normalizeSubscriptionStatus(subscriptionStatus);
        MapSqlParameterSource p = periodParams(from, to)
                .addValue("platformTenantId", tenantId).addValue("platformPlanId", planId)
                .addValue("platformSubscriptionStatus", normalizedStatus);
        PlatformFilter filter = new PlatformFilter(tenantId, planId, normalizedStatus);
        String tf = tenantFilter("t", filter);
        String ef = platformEntityFilter("e.tenant_id", filter);
        String sfEntity = platformEntityFilter("s.tenant_id", filter);
        String cf = platformEntityFilter("c.tenant_id", filter);
        String rf = platformEntityFilter("r.tenant_id", filter);
        String af = platformEntityFilter("a.tenant_id", filter);
        String kpiSql = "SELECT"
                + " (SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL" + tf + ") total_tenants,"
                + " (SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL AND t.created_at>=:fromTs AND t.created_at<:toTs" + tf + ") new_tenants,"
                + " (SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL AND t.status IN ('active','trial')" + tf + ") active_tenants,"
                + " (SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL AND t.status IN ('suspended','cancelled')" + tf + ") suspended_tenants,"
                + " (SELECT COUNT(DISTINCT e.user_id) FROM employees e JOIN users u ON u.id=e.user_id WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL" + ef + ") total_users,"
                + " (SELECT COUNT(DISTINCT e.user_id) FROM employees e JOIN users u ON u.id=e.user_id WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL AND u.last_login_at>=now()-interval '7 days'" + ef + ") active_users_7d,"
                + " (SELECT COUNT(DISTINCT e.user_id) FROM employees e JOIN users u ON u.id=e.user_id WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL AND u.last_login_at>=now()-interval '30 days'" + ef + ") active_users_30d,"
                + " (SELECT COUNT(*) FROM employees e WHERE e.deleted_at IS NULL" + ef + ") employees,"
                + " (SELECT COUNT(*) FROM sites s WHERE s.deleted_at IS NULL" + sfEntity + ") sites,"
                + " (SELECT COUNT(*) FROM checkins c WHERE c.deleted_at IS NULL AND c.check_in_at>=:fromTs AND c.check_in_at<:toTs" + cf + ") checkins";
        Map<String, Object> rawKpis = jdbc.queryForMap(kpiSql, p);
        var kpis = new PlatformCustomerHealthReportResponse.HealthKpis(
                longValue(rawKpis.get("total_tenants")), longValue(rawKpis.get("new_tenants")),
                longValue(rawKpis.get("active_tenants")), longValue(rawKpis.get("suspended_tenants")),
                longValue(rawKpis.get("total_users")), longValue(rawKpis.get("active_users_7d")),
                longValue(rawKpis.get("active_users_30d")), longValue(rawKpis.get("employees")),
                longValue(rawKpis.get("sites")), longValue(rawKpis.get("checkins")));
        String growthSql = """
                WITH months AS (SELECT generate_series(date_trunc('month',CAST(:fromTs AS timestamptz)),
                  date_trunc('month',CAST(:toTs AS timestamptz)-interval '1 day'),interval '1 month') month_start)
                SELECT to_char(month_start,'YYYY-MM') period,
                  (SELECT COUNT(*) FROM tenants t WHERE t.deleted_at IS NULL AND t.created_at>=month_start
                    AND t.created_at<month_start+interval '1 month'%s) new_tenants,
                  (SELECT COUNT(DISTINCT e.user_id) FROM employees e JOIN users u ON u.id=e.user_id
                    WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL AND u.created_at>=month_start
                    AND u.created_at<month_start+interval '1 month'%s) new_users
                FROM months ORDER BY month_start
                """.formatted(tf, ef);
        List<PlatformCustomerHealthReportResponse.GrowthPoint> growth = jdbc.query(growthSql, p,
                (rs, row) -> new PlatformCustomerHealthReportResponse.GrowthPoint(
                        rs.getString("period"), rs.getLong("new_tenants"), rs.getLong("new_users")));
        Map<String, Long> moduleUsage = new LinkedHashMap<>();
        moduleUsage.put("checkins", queryLong("SELECT COUNT(*) FROM checkins c WHERE c.deleted_at IS NULL AND c.check_in_at>=:fromTs AND c.check_in_at<:toTs" + cf, p));
        moduleUsage.put("faceId", queryLong("SELECT COUNT(*) FROM checkins c WHERE c.deleted_at IS NULL AND c.face_verified IS NOT NULL AND c.check_in_at>=:fromTs AND c.check_in_at<:toTs" + cf, p));
        moduleUsage.put("randomChecks", queryLong("SELECT COUNT(*) FROM scheduled_checks r WHERE r.deleted_at IS NULL AND r.created_at>=:fromTs AND r.created_at<:toTs" + rf, p));
        moduleUsage.put("reports", queryLong("SELECT COUNT(*) FROM audit_logs a WHERE a.created_at>=:fromTs AND a.created_at<:toTs AND (a.endpoint LIKE '%/reports/%' OR a.action ILIKE '%export%')" + af, p));
        String tenantHealthSql = ("""
                SELECT t.id tenant_id,t.name tenant_name,COALESCE(pl.display_name,'Chưa có gói') plan_name,
                  COALESCE(ts.status,UPPER(t.status)) subscription_status,COALESCE(ec.amount,0) employees,
                  COALESCE(sc.amount,0) sites,COALESCE(cc.amount,0) checkins_30d,COALESCE(rc.amount,0) random_checks_30d,
                  GREATEST(t.created_at,COALESCE(cc.last_at,t.created_at),COALESCE(ul.last_at,t.created_at)) last_activity,
                  lim.max_employees,lim.max_sites,lim.max_random_checks_per_month
                FROM tenants t LEFT JOIN tenant_subscriptions ts ON ts.tenant_id=t.id
                LEFT JOIN plans pl ON pl.id=ts.plan_id LEFT JOIN plan_limits lim ON lim.plan_id=ts.plan_id
                LEFT JOIN LATERAL (SELECT COUNT(*) amount FROM employees e WHERE e.tenant_id=t.id AND e.deleted_at IS NULL AND e.status='active') ec ON true
                LEFT JOIN LATERAL (SELECT COUNT(*) amount FROM sites s WHERE s.tenant_id=t.id AND s.deleted_at IS NULL AND s.status='active') sc ON true
                LEFT JOIN LATERAL (SELECT COUNT(*) amount,MAX(c.check_in_at) last_at FROM checkins c WHERE c.tenant_id=t.id AND c.deleted_at IS NULL AND c.check_in_at>=now()-interval '30 days') cc ON true
                LEFT JOIN LATERAL (SELECT COUNT(*) amount FROM scheduled_checks r WHERE r.tenant_id=t.id AND r.deleted_at IS NULL AND r.created_at>=now()-interval '30 days') rc ON true
                LEFT JOIN LATERAL (SELECT MAX(u.last_login_at) last_at FROM users u JOIN employees e ON e.user_id=u.id WHERE e.tenant_id=t.id AND e.deleted_at IS NULL) ul ON true
                WHERE t.deleted_at IS NULL
                """) + tf + " ORDER BY t.name";
        List<PlatformCustomerHealthReportResponse.TenantHealth> tenantHealth = jdbc.query(
                tenantHealthSql, p, (rs, row) -> mapTenantHealth(rs));
        tenantHealth.sort(Comparator.comparingInt(PlatformCustomerHealthReportResponse.TenantHealth::healthScore));
        List<PlatformCustomerHealthReportResponse.TenantHealth> atRisk = tenantHealth.stream()
                .filter(t -> !"LOW".equals(t.riskLevel())).limit(20).toList();
        List<PlatformCustomerHealthReportResponse.TenantHealth> nearLimit = tenantHealth.stream()
                .filter(t -> t.maxPlanUsagePercent() >= 80)
                .sorted(Comparator.comparingDouble(PlatformCustomerHealthReportResponse.TenantHealth::maxPlanUsagePercent).reversed())
                .limit(20).toList();
        Map<String, Long> inactivity = new LinkedHashMap<>();
        inactivity.put("7-13 ngày", 0L); inactivity.put("14-29 ngày", 0L); inactivity.put("Từ 30 ngày", 0L);
        tenantHealth.forEach(t -> {
            if (t.inactiveDays() >= 30) inactivity.compute("Từ 30 ngày", (k, v) -> v + 1);
            else if (t.inactiveDays() >= 14) inactivity.compute("14-29 ngày", (k, v) -> v + 1);
            else if (t.inactiveDays() >= 7) inactivity.compute("7-13 ngày", (k, v) -> v + 1);
        });
        return new PlatformCustomerHealthReportResponse(from, to, kpis, growth, moduleUsage,
                inactivity, atRisk, nearLimit);
    }

    public WorkforceEffectivenessReportResponse workforce(UUID tenantId, LocalDate from, LocalDate to,
            UUID siteId, UUID workspaceId, UUID shiftId, UUID employeeId,
            UUID callerUserId, boolean platformAdmin) {
        TenantFilter filter = tenantFilter(tenantId, from, to, siteId, workspaceId, shiftId,
                employeeId, callerUserId, platformAdmin);
        List<WorkforceEffectivenessReportResponse.DailyTrend> daily = workforceDaily(filter);
        WorkforceEffectivenessReportResponse.WorkforceKpis kpis = workforceKpis(
                daily, workforcePresentEmployeeCount(filter));
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        TenantFilter previousFilter = tenantFilter(tenantId, previousFrom, previousTo, siteId,
                workspaceId, shiftId, employeeId, callerUserId, platformAdmin);
        var previous = workforceKpis(workforceDaily(previousFilter),
                workforcePresentEmployeeCount(previousFilter));
        var comparison = new WorkforceEffectivenessReportResponse.Comparison(
                delta(kpis.attendanceRate(), previous.attendanceRate()),
                delta(kpis.lateRate(), previous.lateRate()),
                delta(kpis.absenceRate(), previous.absenceRate()),
                delta(kpis.totalWorkMinutes(), previous.totalWorkMinutes()),
                delta(kpis.totalOtMinutes(), previous.totalOtMinutes()));
        List<WorkforceEffectivenessReportResponse.SiteBreakdown> sites = workforceSites(filter);
        List<WorkforceEffectivenessReportResponse.WeekdayShortage> weekdays = workforceWeekdays(daily);
        return new WorkforceEffectivenessReportResponse(from, to, kpis, comparison, daily, sites, weekdays);
    }

    public RiskComplianceReportResponse risk(UUID tenantId, LocalDate from, LocalDate to,
            UUID siteId, UUID workspaceId, UUID shiftId, UUID employeeId,
            UUID callerUserId, boolean platformAdmin) {
        TenantFilter f = tenantFilter(tenantId, from, to, siteId, workspaceId, shiftId,
                employeeId, callerUserId, platformAdmin);
        String vf = violationFilter("v", f);
        String cf = attendanceEntityFilter("c", f);
        long violations = queryLong("SELECT COUNT(*) FROM violations v JOIN employees e ON e.id=v.employee_id WHERE " + vf, f.params());
        long checkins = queryLong("SELECT COUNT(*) FROM checkins c JOIN employees e ON e.id=c.employee_id WHERE " + cf, f.params());
        Map<String, Object> resolution = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE NOT v.resolved) unresolved,
                       COUNT(*) FILTER (WHERE NOT v.resolved AND v.created_at < now()-interval '24 hours') overdue,
                       COALESCE(AVG(EXTRACT(EPOCH FROM (v.resolved_at-v.created_at))/3600) FILTER (WHERE v.resolved_at IS NOT NULL),0) avg_hours,
                       COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (v.resolved_at-v.created_at))/3600) FILTER (WHERE v.resolved_at IS NOT NULL),0) median_hours,
                       COUNT(*) FILTER (WHERE v.affects_attendance) attendance_impact,
                       COUNT(*) FILTER (WHERE v.employee_note IS NOT NULL AND v.resolution='dismissed') accepted,
                       COUNT(*) FILTER (WHERE v.employee_note IS NOT NULL AND v.resolution IS NOT NULL) explained_resolved
                FROM violations v JOIN employees e ON e.id=v.employee_id WHERE
                """ + " " + vf, f.params());
        Map<String, Long> byType = groupedCounts(
                "SELECT v.violation_type label, COUNT(*) amount FROM violations v JOIN employees e ON e.id=v.employee_id WHERE "
                        + vf + " GROUP BY v.violation_type", f.params(),
                List.of("location_fail", "face_fail", "liveness_fail", "no_response", "face_verify_timeout"));
        Map<String, Long> aging = new LinkedHashMap<>();
        Map<String, Object> age = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE now()-v.created_at < interval '24 hours') under_24h,
                       COUNT(*) FILTER (WHERE now()-v.created_at >= interval '24 hours' AND now()-v.created_at < interval '3 days') d1_3,
                       COUNT(*) FILTER (WHERE now()-v.created_at >= interval '3 days' AND now()-v.created_at < interval '7 days') d3_7,
                       COUNT(*) FILTER (WHERE now()-v.created_at >= interval '7 days') over_7d
                FROM violations v JOIN employees e ON e.id=v.employee_id WHERE NOT v.resolved AND
                """ + " " + vf, f.params());
        aging.put("Dưới 24 giờ", longValue(age.get("under_24h")));
        aging.put("1-3 ngày", longValue(age.get("d1_3")));
        aging.put("3-7 ngày", longValue(age.get("d3_7")));
        aging.put("Trên 7 ngày", longValue(age.get("over_7d")));

        Map<String, Object> random = jdbc.queryForMap("""
                SELECT COUNT(*) total, COUNT(*) FILTER (WHERE cr.outcome='pass') passed
                FROM check_responses cr JOIN scheduled_checks sc ON sc.id=cr.scheduled_check_id
                JOIN employees e ON e.id=cr.employee_id
                WHERE cr.tenant_id=:tenantId AND cr.created_at>=:fromTs AND cr.created_at<:toTs
                """ + randomCheckFilter(f), f.params());
        long scopedEmployees = scopedEmployeeCount(f);
        long enrolled = queryLong("""
                SELECT COUNT(DISTINCT fp.employee_id) FROM face_profiles fp JOIN employees e ON e.id=fp.employee_id
                WHERE fp.tenant_id=:tenantId AND fp.status='enrolled' AND e.deleted_at IS NULL
                """ + employeeScopeFilter(f), f.params());
        var kpis = new RiskComplianceReportResponse.RiskKpis(
                violations, checkins, per100(violations, checkins), longValue(resolution.get("unresolved")),
                longValue(resolution.get("overdue")), doubleValue(resolution.get("avg_hours")),
                doubleValue(resolution.get("median_hours")),
                percent(longValue(resolution.get("accepted")), longValue(resolution.get("explained_resolved"))),
                percent(longValue(random.get("passed")), longValue(random.get("total"))),
                percent(enrolled, scopedEmployees), longValue(resolution.get("attendance_impact")));
        List<RiskComplianceReportResponse.RiskTrendPoint> trend = jdbc.query("""
                SELECT v.check_date, v.violation_type, COUNT(*) amount
                FROM violations v JOIN employees e ON e.id=v.employee_id WHERE
                """ + " " + vf
                        + " GROUP BY v.check_date,v.violation_type ORDER BY v.check_date,v.violation_type",
                f.params(), (rs, row) -> new RiskComplianceReportResponse.RiskTrendPoint(
                        rs.getDate("check_date").toLocalDate(), rs.getString("violation_type"), rs.getLong("amount")));
        List<RiskComplianceReportResponse.SiteRisk> siteRisk = jdbc.query(
                "SELECT s.id site_id,s.name site_name,v.violation_type,COUNT(*) amount,"
                + " COALESCE((SELECT COUNT(*) FROM checkins c JOIN employees ce ON ce.id=c.employee_id"
                + " WHERE c.site_id=s.id AND " + siteRiskCheckinFilter(f) + "),0) checkins"
                + " FROM violations v JOIN employees e ON e.id=v.employee_id JOIN sites s ON s.id=v.site_id"
                + " WHERE " + vf
                + " GROUP BY s.id,s.name,v.violation_type ORDER BY amount DESC LIMIT 50",
                f.params(), (rs, row) -> new RiskComplianceReportResponse.SiteRisk(
                        rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                        rs.getString("violation_type"), rs.getLong("amount"),
                        per100(rs.getLong("amount"), rs.getLong("checkins"))));
        List<RiskComplianceReportResponse.RepeatOffender> offenders = jdbc.query("""
                SELECT e.id,e.last_name || ' ' || e.first_name employee_name,e.employee_code,
                       COUNT(*) amount,COUNT(*) FILTER (WHERE NOT v.resolved) unresolved
                FROM violations v JOIN employees e ON e.id=v.employee_id WHERE
                """ + " " + vf
                        + " GROUP BY e.id,e.last_name,e.first_name,e.employee_code HAVING COUNT(*)>1 ORDER BY amount DESC LIMIT 15",
                f.params(), (rs, row) -> new RiskComplianceReportResponse.RepeatOffender(
                        rs.getObject("id", UUID.class), rs.getString("employee_name"),
                        rs.getString("employee_code"), rs.getLong("amount"), rs.getLong("unresolved")));
        long explained = queryLong("SELECT COUNT(*) FROM violations v JOIN employees e ON e.id=v.employee_id WHERE v.employee_note IS NOT NULL AND " + vf, f.params());
        long reviewed = queryLong("SELECT COUNT(*) FROM violations v JOIN employees e ON e.id=v.employee_id WHERE v.resolution IS NOT NULL AND " + vf, f.params());
        var funnel = new RiskComplianceReportResponse.Funnel(violations, explained, reviewed,
                queryLong("SELECT COUNT(*) FROM violations v JOIN employees e ON e.id=v.employee_id WHERE v.resolution='confirmed' AND " + vf, f.params()),
                queryLong("SELECT COUNT(*) FROM violations v JOIN employees e ON e.id=v.employee_id WHERE v.resolution='dismissed' AND " + vf, f.params()));
        return new RiskComplianceReportResponse(from, to, kpis, byType, aging, funnel, trend,
                siteRisk, offenders);
    }

    private List<WorkforceEffectivenessReportResponse.DailyTrend> workforceDaily(TenantFilter f) {
        String af = assignmentFilter("a", f);
        String sf = attendanceEntityFilter("x", f);
        return jdbc.query("""
                WITH days AS (SELECT generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day')::date report_date),
                expected AS (
                  SELECT d.report_date,COUNT(DISTINCT (a.employee_id,a.site_id)) amount FROM days d
                  JOIN assignments a ON a.start_date<=d.report_date AND (a.end_date IS NULL OR a.end_date>=d.report_date)
                    AND a.status='active' AND a.deleted_at IS NULL
                    AND (a.days_of_week IS NULL OR (a.days_of_week & (1 << (EXTRACT(ISODOW FROM d.report_date)::int-1)))<>0)
                  JOIN employees e ON e.id=a.employee_id AND e.deleted_at IS NULL AND e.status='active'
                  WHERE
                """ + " " + af + " GROUP BY d.report_date), attendance AS ("
                + " SELECT x.attendance_date attendance_day,COUNT(DISTINCT (x.employee_id,x.site_id)) present,"
                + " COUNT(DISTINCT (x.employee_id,x.site_id)) FILTER (WHERE x.is_late) late,"
                + " COUNT(DISTINCT (x.employee_id,x.site_id)) FILTER (WHERE x.is_early_leave) early_leave,"
                + " COUNT(DISTINCT (x.employee_id,x.site_id)) FILTER (WHERE x.missing_checkout) missing_checkout,"
                + " COALESCE(SUM(x.total_work_minutes),0) work_minutes,COALESCE(SUM(x.ot_minutes),0) ot_minutes"
                + " FROM attendance_summaries x JOIN employees e ON e.id=x.employee_id WHERE " + sf
                + " GROUP BY x.attendance_date)"
                + " SELECT d.report_date,COALESCE(ex.amount,0) assigned,COALESCE(at.present,0) present,"
                + " GREATEST(COALESCE(ex.amount,0)-COALESCE(at.present,0),0) absent,"
                + " COALESCE(at.late,0) late,COALESCE(at.early_leave,0) early_leave,"
                + " COALESCE(at.missing_checkout,0) missing_checkout,COALESCE(at.work_minutes,0) work_minutes,"
                + " COALESCE(at.ot_minutes,0) ot_minutes FROM days d LEFT JOIN expected ex ON ex.report_date=d.report_date"
                + " LEFT JOIN attendance at ON at.attendance_day=d.report_date ORDER BY d.report_date",
                f.params(), (rs, row) -> new WorkforceEffectivenessReportResponse.DailyTrend(
                        rs.getDate("report_date").toLocalDate(), rs.getLong("assigned"), rs.getLong("present"),
                        rs.getLong("absent"), rs.getLong("late"), rs.getLong("early_leave"),
                        rs.getLong("missing_checkout"), rs.getLong("work_minutes"), rs.getLong("ot_minutes")));
    }

    private WorkforceEffectivenessReportResponse.WorkforceKpis workforceKpis(
            List<WorkforceEffectivenessReportResponse.DailyTrend> daily,
            long distinctPresentEmployees) {
        long assigned = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::assigned).sum();
        long present = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::present).sum();
        long absent = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::absent).sum();
        long late = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::late).sum();
        long early = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::earlyLeave).sum();
        long missing = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::missingCheckout).sum();
        long work = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::workMinutes).sum();
        long ot = daily.stream().mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::otMinutes).sum();
        return new WorkforceEffectivenessReportResponse.WorkforceKpis(
                assigned, present, absent, percent(present, assigned), percent(absent, assigned),
                percent(late, present), percent(early, present), percent(missing, present),
                work, ot, distinctPresentEmployees == 0 ? 0 : work / distinctPresentEmployees);
    }

    private long workforcePresentEmployeeCount(TenantFilter f) {
        return queryLong("SELECT COUNT(DISTINCT x.employee_id) FROM attendance_summaries x "
                + "JOIN employees e ON e.id=x.employee_id WHERE " + attendanceEntityFilter("x", f),
                f.params());
    }

    private List<WorkforceEffectivenessReportResponse.SiteBreakdown> workforceSites(TenantFilter f) {
        String af = assignmentFilter("a", f);
        String sf = attendanceEntityFilter("x", f);
        return jdbc.query("""
                WITH days AS (SELECT generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day')::date report_date),
                expected AS (SELECT a.site_id,COUNT(DISTINCT (d.report_date,a.employee_id)) amount FROM days d JOIN assignments a
                  ON a.start_date<=d.report_date AND (a.end_date IS NULL OR a.end_date>=d.report_date)
                  AND a.status='active' AND a.deleted_at IS NULL
                  AND (a.days_of_week IS NULL OR (a.days_of_week & (1 << (EXTRACT(ISODOW FROM d.report_date)::int-1)))<>0)
                  JOIN employees e ON e.id=a.employee_id AND e.deleted_at IS NULL AND e.status='active'
                  WHERE
                """ + " " + af + " GROUP BY a.site_id), attendance AS ("
                + " SELECT x.site_id,COUNT(DISTINCT (x.employee_id,x.attendance_date)) present,"
                + " COALESCE(SUM(x.total_work_minutes),0) work_minutes,COALESCE(SUM(x.ot_minutes),0) ot_minutes"
                + " FROM attendance_summaries x JOIN employees e ON e.id=x.employee_id WHERE " + sf + " GROUP BY x.site_id)"
                + " SELECT s.id,s.name,COALESCE(ex.amount,0) assigned,COALESCE(at.present,0) present,"
                + " GREATEST(COALESCE(ex.amount,0)-COALESCE(at.present,0),0) absent,"
                + " COALESCE(at.work_minutes,0) work_minutes,COALESCE(at.ot_minutes,0) ot_minutes"
                + " FROM sites s LEFT JOIN expected ex ON ex.site_id=s.id LEFT JOIN attendance at ON at.site_id=s.id"
                + " WHERE s.tenant_id=:tenantId AND s.deleted_at IS NULL AND (ex.amount IS NOT NULL OR at.present IS NOT NULL)"
                + " ORDER BY absent DESC,s.name",
                f.params(), (rs, row) -> new WorkforceEffectivenessReportResponse.SiteBreakdown(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("assigned"),
                        rs.getLong("present"), rs.getLong("absent"),
                        percent(rs.getLong("present"), rs.getLong("assigned")),
                        rs.getLong("work_minutes"), rs.getLong("ot_minutes")));
    }

    private List<WorkforceEffectivenessReportResponse.WeekdayShortage> workforceWeekdays(
            List<WorkforceEffectivenessReportResponse.DailyTrend> daily) {
        String[] labels = {"", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ Nhật"};
        List<WorkforceEffectivenessReportResponse.WeekdayShortage> result = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            final int isoDay = day;
            long assigned = daily.stream().filter(x -> x.date().getDayOfWeek().getValue() == isoDay)
                    .mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::assigned).sum();
            long absent = daily.stream().filter(x -> x.date().getDayOfWeek().getValue() == isoDay)
                    .mapToLong(WorkforceEffectivenessReportResponse.DailyTrend::absent).sum();
            result.add(new WorkforceEffectivenessReportResponse.WeekdayShortage(
                    day, labels[day], assigned, absent, percent(absent, assigned)));
        }
        return result;
    }

    private PlatformCustomerHealthReportResponse.TenantHealth mapTenantHealth(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        long employees = rs.getLong("employees");
        long sites = rs.getLong("sites");
        long randomChecks = rs.getLong("random_checks_30d");
        double usage = Math.max(ratioToPercent(employees, nullableLong(rs, "max_employees")),
                Math.max(ratioToPercent(sites, nullableLong(rs, "max_sites")),
                        ratioToPercent(randomChecks, nullableLong(rs, "max_random_checks_per_month"))));
        OffsetDateTime last = rs.getObject("last_activity", OffsetDateTime.class);
        long inactiveDays = Math.max(0, java.time.Duration.between(last, OffsetDateTime.now()).toDays());
        String subscription = rs.getString("subscription_status");
        int score = 100;
        if (inactiveDays >= 30) score -= 45; else if (inactiveDays >= 14) score -= 25; else if (inactiveDays >= 7) score -= 10;
        if (Set.of("EXPIRED", "CANCELLED", "SUSPENDED").contains(subscription.toUpperCase())) score -= 35;
        if (rs.getLong("checkins_30d") == 0) score -= 15;
        if (usage >= 100) score -= 10;
        score = Math.max(0, score);
        String risk = score < 50 ? "HIGH" : score < 75 ? "MEDIUM" : "LOW";
        String action = "HIGH".equals(risk) ? "Liên hệ và rà soát khả năng gia hạn"
                : usage >= 80 ? "Tư vấn nâng gói trước khi chạm giới hạn"
                : "MEDIUM".equals(risk) ? "Hỗ trợ kích hoạt lại mức sử dụng" : "Tiếp tục theo dõi";
        return new PlatformCustomerHealthReportResponse.TenantHealth(
                rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"),
                rs.getString("plan_name"), subscription, score, risk, round(usage), employees,
                sites, rs.getLong("checkins_30d"), randomChecks, last, inactiveDays, action);
    }

    private TenantFilter tenantFilter(UUID tenantId, LocalDate from, LocalDate to, UUID siteId,
            UUID workspaceId, UUID shiftId, UUID employeeId, UUID callerUserId, boolean platformAdmin) {
        if (!platformAdmin) {
            Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("reports:list")) throw new AccessDeniedException("reports:list permission required");
        }
        Optional<Set<UUID>> allowed = siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, platformAdmin);
        if (siteId != null && allowed.isPresent() && !allowed.get().contains(siteId)) {
            throw new AccessDeniedException("You do not have access to this site");
        }
        MapSqlParameterSource params = periodParams(from, to)
                .addValue("tenantId", tenantId).addValue("fromDate", from).addValue("toDate", to)
                .addValue("siteId", siteId).addValue("workspaceId", workspaceId)
                .addValue("shiftId", shiftId).addValue("employeeId", employeeId);
        allowed.ifPresent(ids -> params.addValue("allowedSiteIds", ids.isEmpty() ? Set.of(EMPTY_SCOPE_SENTINEL) : ids));
        return new TenantFilter(params, siteId, workspaceId, shiftId, employeeId, allowed);
    }

    private String assignmentFilter(String alias, TenantFilter f) {
        StringBuilder sql = new StringBuilder(alias + ".tenant_id=:tenantId");
        appendEntityFilters(sql, alias, f);
        return sql.toString();
    }

    private String attendanceEntityFilter(String alias, TenantFilter f) {
        String dateColumn = "x".equals(alias) ? alias + ".attendance_date" : alias + ".check_in_at";
        StringBuilder sql = new StringBuilder(alias + ".tenant_id=:tenantId AND " + alias + ".deleted_at IS NULL");
        if ("x".equals(alias)) sql.append(" AND ").append(dateColumn).append(" BETWEEN :fromDate AND :toDate");
        else sql.append(" AND ").append(dateColumn).append(">=:fromTs AND ").append(dateColumn).append("<:toTs");
        appendEntityFilters(sql, alias, f);
        return sql.toString();
    }

    private String violationFilter(String alias, TenantFilter f) {
        StringBuilder sql = new StringBuilder(alias + ".tenant_id=:tenantId AND " + alias
                + ".deleted_at IS NULL AND " + alias + ".check_date BETWEEN :fromDate AND :toDate");
        appendEntityFilters(sql, alias, f);
        if (f.shiftId() != null) {
            sql.append(" AND (EXISTS (SELECT 1 FROM scheduled_checks vsc WHERE vsc.id=")
                    .append(alias).append(".scheduled_check_id AND vsc.shift_id=:shiftId) OR EXISTS ")
                    .append("(SELECT 1 FROM checkins vc WHERE vc.id=").append(alias)
                    .append(".checkin_id AND vc.shift_id=:shiftId))");
        }
        return sql.toString();
    }

    private void appendEntityFilters(StringBuilder sql, String alias, TenantFilter f) {
        if (f.siteId() != null) sql.append(" AND ").append(alias).append(".site_id=:siteId");
        if (f.shiftId() != null && !"v".equals(alias)) sql.append(" AND ").append(alias).append(".shift_id=:shiftId");
        if (f.employeeId() != null) sql.append(" AND ").append(alias).append(".employee_id=:employeeId");
        if (f.workspaceId() != null) sql.append(" AND e.department_id=:workspaceId");
        if (f.allowedSiteIds().isPresent()) sql.append(" AND ").append(alias).append(".site_id IN (:allowedSiteIds)");
        if ("a".equals(alias)) sql.append(" AND a.start_date<=:toDate AND (a.end_date IS NULL OR a.end_date>=:fromDate)");
    }

    private String employeeOnlyFilter(String alias, TenantFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.employeeId() != null) sql.append(" AND ").append(alias).append(".employee_id=:employeeId");
        if (f.workspaceId() != null) sql.append(" AND e.department_id=:workspaceId");
        if (f.siteId() != null || f.allowedSiteIds().isPresent() || f.shiftId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM assignments ax WHERE ax.employee_id=")
                    .append(alias).append(".employee_id AND ax.tenant_id=:tenantId AND ax.deleted_at IS NULL");
            if (f.siteId() != null) sql.append(" AND ax.site_id=:siteId");
            if (f.shiftId() != null) sql.append(" AND ax.shift_id=:shiftId");
            if (f.allowedSiteIds().isPresent()) sql.append(" AND ax.site_id IN (:allowedSiteIds)");
            sql.append(")");
        }
        return sql.toString();
    }

    private String randomCheckFilter(TenantFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.siteId() != null) sql.append(" AND sc.site_id=:siteId");
        if (f.shiftId() != null) sql.append(" AND sc.shift_id=:shiftId");
        if (f.employeeId() != null) sql.append(" AND cr.employee_id=:employeeId");
        if (f.workspaceId() != null) sql.append(" AND e.department_id=:workspaceId");
        if (f.allowedSiteIds().isPresent()) sql.append(" AND sc.site_id IN (:allowedSiteIds)");
        return sql.toString();
    }

    private String siteRiskCheckinFilter(TenantFilter f) {
        StringBuilder sql = new StringBuilder(
                "c.tenant_id=:tenantId AND c.deleted_at IS NULL AND c.check_in_at>=:fromTs AND c.check_in_at<:toTs");
        if (f.shiftId() != null) sql.append(" AND c.shift_id=:shiftId");
        if (f.employeeId() != null) sql.append(" AND c.employee_id=:employeeId");
        if (f.workspaceId() != null) sql.append(" AND ce.department_id=:workspaceId");
        if (f.allowedSiteIds().isPresent()) sql.append(" AND c.site_id IN (:allowedSiteIds)");
        return sql.toString();
    }

    private String employeeScopeFilter(TenantFilter f) { return employeeOnlyFilter("fp", f); }

    private long scopedEmployeeCount(TenantFilter f) {
        return queryLong("SELECT COUNT(*) FROM employees e WHERE e.tenant_id=:tenantId AND e.deleted_at IS NULL AND e.status='active'"
                + (f.employeeId() != null ? " AND e.id=:employeeId" : "")
                + (f.workspaceId() != null ? " AND e.department_id=:workspaceId" : "")
                + ((f.siteId() != null || f.allowedSiteIds().isPresent() || f.shiftId() != null)
                ? " AND EXISTS (SELECT 1 FROM assignments ax WHERE ax.employee_id=e.id AND ax.deleted_at IS NULL"
                  + (f.siteId() != null ? " AND ax.site_id=:siteId" : "")
                  + (f.shiftId() != null ? " AND ax.shift_id=:shiftId" : "")
                + (f.allowedSiteIds().isPresent() ? " AND ax.site_id IN (:allowedSiteIds)" : "") + ")" : ""), f.params());
    }

    private String billingFilter(String alias, PlatformFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.tenantId() != null) sql.append(" AND ").append(alias).append(".tenant_id=:platformTenantId");
        if (f.planId() != null) sql.append(" AND ").append(alias).append(".plan_id=:platformPlanId");
        if (f.subscriptionStatus() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM tenant_subscriptions pfs WHERE pfs.tenant_id=")
                    .append(alias).append(".tenant_id AND pfs.status=:platformSubscriptionStatus)");
        }
        return sql.toString();
    }

    private String subscriptionFilter(String alias, PlatformFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.tenantId() != null) sql.append(" AND ").append(alias).append(".tenant_id=:platformTenantId");
        if (f.planId() != null) sql.append(" AND ").append(alias).append(".plan_id=:platformPlanId");
        if (f.subscriptionStatus() != null) sql.append(" AND ").append(alias).append(".status=:platformSubscriptionStatus");
        return sql.toString();
    }

    private String tenantFilter(String alias, PlatformFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.tenantId() != null) sql.append(" AND ").append(alias).append(".id=:platformTenantId");
        if (f.planId() != null || f.subscriptionStatus() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM tenant_subscriptions pfs WHERE pfs.tenant_id=")
                    .append(alias).append(".id");
            if (f.planId() != null) sql.append(" AND pfs.plan_id=:platformPlanId");
            if (f.subscriptionStatus() != null) sql.append(" AND pfs.status=:platformSubscriptionStatus");
            sql.append(")");
        }
        return sql.toString();
    }

    private String platformEntityFilter(String tenantColumn, PlatformFilter f) {
        StringBuilder sql = new StringBuilder();
        if (f.tenantId() != null) sql.append(" AND ").append(tenantColumn).append("=:platformTenantId");
        if (f.planId() != null || f.subscriptionStatus() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM tenant_subscriptions pfs WHERE pfs.tenant_id=")
                    .append(tenantColumn);
            if (f.planId() != null) sql.append(" AND pfs.plan_id=:platformPlanId");
            if (f.subscriptionStatus() != null) sql.append(" AND pfs.status=:platformSubscriptionStatus");
            sql.append(")");
        }
        return sql.toString();
    }

    private String normalizeSubscriptionStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase();
        if (!Set.of("TRIAL", "ACTIVE", "EXPIRED", "CANCELLED").contains(normalized)) {
            throw new IllegalArgumentException("subscriptionStatus is not supported");
        }
        return normalized;
    }

    private MapSqlParameterSource periodParams(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("fromTs", from.atStartOfDay(VIETNAM).toOffsetDateTime())
                .addValue("toTs", to.plusDays(1).atStartOfDay(VIETNAM).toOffsetDateTime());
    }

    private Map<String, Long> groupedCounts(String sql, MapSqlParameterSource params, List<String> defaults) {
        Map<String, Long> result = new LinkedHashMap<>();
        defaults.forEach(key -> result.put(key, 0L));
        jdbc.query(sql, params, (rs, row) -> Map.entry(rs.getString("label"), rs.getLong("amount")))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private long queryLong(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static long longValue(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static double doubleValue(Object value) { return value == null ? 0 : round(((Number) value).doubleValue()); }
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static double ratioToPercent(long value, Long limit) { return limit == null || limit <= 0 ? 0 : value * 100d / limit; }
    private static double percent(long numerator, long denominator) { return denominator <= 0 ? 0 : round(numerator * 100d / denominator); }
    private static double per100(long numerator, long denominator) { return percent(numerator, denominator); }
    private static double delta(double current, double previous) { return round(current - previous); }
    private static double delta(long current, long previous) { return previous == 0 ? (current == 0 ? 0 : 100) : round((current - previous) * 100d / previous); }
    private static double round(double value) { return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue(); }

    private record TenantFilter(MapSqlParameterSource params, UUID siteId, UUID workspaceId,
                                UUID shiftId, UUID employeeId, Optional<Set<UUID>> allowedSiteIds) {}
    private record PlatformFilter(UUID tenantId, UUID planId, String subscriptionStatus) {}
}
