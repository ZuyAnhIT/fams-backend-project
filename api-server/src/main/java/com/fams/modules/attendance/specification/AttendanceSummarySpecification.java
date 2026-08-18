package com.fams.modules.attendance.specification;

import com.fams.modules.attendance.entity.AttendanceSummary;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

public class AttendanceSummarySpecification {

    private AttendanceSummarySpecification() {}

    public static Specification<AttendanceSummary> build(UUID tenantId, UUID employeeId,
                                                          UUID siteId, String status,
                                                          LocalDate from, LocalDate to) {
        return build(tenantId, employeeId, siteId, status, from, to, null);
    }

    /** @param employeeIds found via audit (2026-08-18) — workspace filter for reports: a
     *  workspace has no direct column on AttendanceSummary (relationship is via
     *  WorkspaceMember), so the caller resolves the workspace's member employee IDs first and
     *  passes them in here. Null means no workspace filter (unchanged default behavior). */
    public static Specification<AttendanceSummary> build(UUID tenantId, UUID employeeId,
                                                          UUID siteId, String status,
                                                          LocalDate from, LocalDate to,
                                                          Collection<UUID> employeeIds) {
        return tenantEq(tenantId)
                .and(notDeleted())
                .and(employeeEq(employeeId))
                .and(employeeIdIn(employeeIds))
                .and(siteEq(siteId))
                .and(statusEq(status))
                .and(dateFrom(from))
                .and(dateTo(to));
    }

    private static Specification<AttendanceSummary> employeeIdIn(Collection<UUID> employeeIds) {
        if (employeeIds == null) return null;
        return (root, query, cb) -> root.get("employeeId").in(employeeIds);
    }

    private static Specification<AttendanceSummary> tenantEq(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    private static Specification<AttendanceSummary> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    private static Specification<AttendanceSummary> employeeEq(UUID employeeId) {
        if (employeeId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("employeeId"), employeeId);
    }

    private static Specification<AttendanceSummary> siteEq(UUID siteId) {
        if (siteId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("siteId"), siteId);
    }

    private static Specification<AttendanceSummary> statusEq(String status) {
        if (status == null || status.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<AttendanceSummary> dateFrom(LocalDate from) {
        if (from == null) return null;
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("attendanceDate"), from);
    }

    private static Specification<AttendanceSummary> dateTo(LocalDate to) {
        if (to == null) return null;
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("attendanceDate"), to);
    }
}
