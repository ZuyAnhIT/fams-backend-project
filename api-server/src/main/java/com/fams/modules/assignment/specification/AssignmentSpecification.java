package com.fams.modules.assignment.specification;

import com.fams.modules.assignment.entity.Assignment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AssignmentSpecification {

    private AssignmentSpecification() {}

    public static Specification<Assignment> build(UUID siteId, UUID tenantId,
                                                   String status, String role,
                                                   UUID employeeId, UUID shiftId,
                                                   LocalDate dateRangeFrom, LocalDate dateRangeTo) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("siteId"), siteId));
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(role)) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (employeeId != null) {
                predicates.add(cb.equal(root.get("employeeId"), employeeId));
            }
            if (shiftId != null) {
                predicates.add(cb.equal(root.get("shiftId"), shiftId));
            }
            // "Active during [dateRangeFrom, dateRangeTo]" — an overlap test, not an exact-match
            // test, so an assignment spanning the whole window (or beyond it) is still included.
            if (dateRangeFrom != null) {
                predicates.add(cb.or(
                        cb.isNull(root.get("endDate")),
                        cb.greaterThanOrEqualTo(root.get("endDate"), dateRangeFrom)));
            }
            if (dateRangeTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), dateRangeTo));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
