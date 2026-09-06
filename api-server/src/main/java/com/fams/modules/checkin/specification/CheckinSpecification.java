package com.fams.modules.checkin.specification;

import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.employee.entity.Employee;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CheckinSpecification {

    private CheckinSpecification() {}

    public static Specification<CheckinRecord> build(UUID tenantId, UUID employeeId,
                                                      UUID siteId, UUID workspaceId, UUID shiftId,
                                                      String status,
                                                      OffsetDateTime from, OffsetDateTime to) {
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
            if (shiftId != null) {
                predicates.add(cb.equal(root.get("shiftId"), shiftId));
            }
            if (workspaceId != null) {
                Subquery<UUID> scopedEmployees = query.subquery(UUID.class);
                Root<Employee> employee = scopedEmployees.from(Employee.class);
                scopedEmployees.select(employee.get("id")).where(
                        cb.equal(employee.get("tenantId"), tenantId),
                        cb.equal(employee.get("departmentId"), workspaceId),
                        cb.isNull(employee.get("deletedAt"))
                );
                predicates.add(root.get("employeeId").in(scopedEmployees));
            }
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkInAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("checkInAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
