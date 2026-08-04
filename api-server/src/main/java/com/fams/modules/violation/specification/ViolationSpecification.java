package com.fams.modules.violation.specification;

import com.fams.modules.violation.entity.Violation;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
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
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (employeeId != null) {
                predicates.add(cb.equal(root.get("employeeId"), employeeId));
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
