package com.fams.modules.attendance.specification;

import com.fams.modules.attendance.entity.AttendanceSummary;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.UUID;

public class AttendanceSummarySpecification {

    private AttendanceSummarySpecification() {}

    public static Specification<AttendanceSummary> build(UUID tenantId, UUID employeeId,
                                                          UUID siteId, String status,
                                                          LocalDate from, LocalDate to) {
        return tenantEq(tenantId)
                .and(notDeleted())
                .and(employeeEq(employeeId))
                .and(siteEq(siteId))
                .and(statusEq(status))
                .and(dateFrom(from))
                .and(dateTo(to));
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
