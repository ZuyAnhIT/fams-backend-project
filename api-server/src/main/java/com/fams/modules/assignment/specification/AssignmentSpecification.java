package com.fams.modules.assignment.specification;

import com.fams.modules.assignment.entity.Assignment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AssignmentSpecification {

    private AssignmentSpecification() {}

    public static Specification<Assignment> build(UUID siteId, UUID tenantId,
                                                   String status, String role,
                                                   UUID employeeId, UUID shiftId) {
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

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
