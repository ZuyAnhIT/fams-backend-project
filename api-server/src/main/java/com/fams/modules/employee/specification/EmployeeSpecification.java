package com.fams.modules.employee.specification;

import com.fams.modules.employee.entity.Employee;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class EmployeeSpecification {

    private EmployeeSpecification() {}

    public static Specification<Employee> build(UUID tenantId, String search, String status, String department) {
        return build(tenantId, search, status, department, null);
    }

    /**
     * @param restrictToEmployeeIds null = no site-scope restriction; a non-null collection
     *                              restricts results to those employee IDs (resolved from the
     *                              caller's allowed sites via Assignment — see
     *                              EmployeeService). Callers must short-circuit an EMPTY
     *                              collection themselves — Criteria's {@code id IN ()} is invalid.
     */
    public static Specification<Employee> build(UUID tenantId, String search, String status, String department,
                                                 Collection<UUID> restrictToEmployeeIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (restrictToEmployeeIds != null) {
                predicates.add(root.get("id").in(restrictToEmployeeIds));
            }

            if (StringUtils.hasText(search)) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), like),
                        cb.like(cb.lower(root.get("lastName")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("employeeCode")), like),
                        cb.like(cb.lower(root.get("position")), like)
                ));
            }

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (StringUtils.hasText(department)) {
                predicates.add(cb.equal(cb.lower(root.get("department")), department.toLowerCase()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
