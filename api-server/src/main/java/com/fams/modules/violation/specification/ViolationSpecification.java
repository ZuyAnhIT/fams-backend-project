package com.fams.modules.violation.specification;

import com.fams.modules.violation.entity.Violation;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class ViolationSpecification {

    private ViolationSpecification() {}

    public static Specification<Violation> build(UUID tenantId, UUID employeeId, UUID siteId,
                                                  String violationType, Boolean resolved,
                                                  LocalDate from, LocalDate to) {
        return build(tenantId, employeeId, siteId, violationType, resolved, from, to, null);
    }

    /** @param scheduledCheckId found via audit (2026-08-02) — precise dispute-resolution lookup:
     *  "which violation(s), if any, came from THIS exact scheduled check", instead of HR having
     *  to filter by employeeId+date range and guess which row matches. */
    public static Specification<Violation> build(UUID tenantId, UUID employeeId, UUID siteId,
                                                  String violationType, Boolean resolved,
                                                  LocalDate from, LocalDate to,
                                                  UUID scheduledCheckId) {
        return build(tenantId, employeeId, siteId, violationType, resolved, from, to, scheduledCheckId, null);
    }

    /** @param affectsAttendance found via audit (2026-08-18): the AC calls for filtering by this,
     *  and unlike `severity` (a computed/derived value with no backing column — see #114) this
     *  field genuinely exists on the entity already; the gap was purely a missing predicate. */
    public static Specification<Violation> build(UUID tenantId, UUID employeeId, UUID siteId,
                                                  String violationType, Boolean resolved,
                                                  LocalDate from, LocalDate to,
                                                  UUID scheduledCheckId, Boolean affectsAttendance) {
        return build(tenantId, employeeId, siteId, violationType, resolved, from, to,
                scheduledCheckId, affectsAttendance, null);
    }

    /** @param employeeIds found via audit (2026-08-18) — workspace filter for the violation
     *  report: a workspace has no direct column on Violation (relationship is via
     *  WorkspaceMember), so the caller resolves the workspace's member employee IDs first and
     *  passes them in here. Null means no workspace filter. */
    public static Specification<Violation> build(UUID tenantId, UUID employeeId, UUID siteId,
                                                  String violationType, Boolean resolved,
                                                  LocalDate from, LocalDate to,
                                                  UUID scheduledCheckId, Boolean affectsAttendance,
                                                  Collection<UUID> employeeIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (employeeId != null) {
                predicates.add(cb.equal(root.get("employeeId"), employeeId));
            }
            if (employeeIds != null) {
                predicates.add(root.get("employeeId").in(employeeIds));
            }
            if (siteId != null) {
                predicates.add(cb.equal(root.get("siteId"), siteId));
            }
            if (scheduledCheckId != null) {
                predicates.add(cb.equal(root.get("scheduledCheckId"), scheduledCheckId));
            }
            if (StringUtils.hasText(violationType)) {
                predicates.add(cb.equal(root.get("violationType"), violationType));
            }
            if (resolved != null) {
                predicates.add(cb.equal(root.get("resolved"), resolved));
            }
            if (affectsAttendance != null) {
                predicates.add(cb.equal(root.get("affectsAttendance"), affectsAttendance));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("checkDate"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
