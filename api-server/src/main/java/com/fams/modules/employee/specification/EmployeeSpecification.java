package com.fams.modules.employee.specification;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.workspace.entity.WorkspaceMember;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class EmployeeSpecification {

    private EmployeeSpecification() {}

    public static Specification<Employee> build(UUID tenantId, String search, String status, String department) {
        return build(tenantId, search, status, department, null, null, null);
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
        return build(tenantId, search, status, department, restrictToEmployeeIds, null, null);
    }

    /**
     * @param faceRegistered null = no filter; true = only employees with an ENROLLED face
     *                       profile (face_profiles.status = 'enrolled'); false = employees with
     *                       no face profile row at all, OR one that isn't enrolled (not_enrolled
     *                       /revoked) — #36 gap fix (2026-08-15), the AC always required this
     *                       filter but it never existed.
     * @param workspaceId    null = no filter; otherwise restricts to employees with an ACTIVE
     *                       WorkspaceMember row for this workspace — deliberately separate from
     *                       the pre-existing {@code department} filter (which only matches
     *                       Employee.department, a denormalized primary-department name, and
     *                       doesn't see secondary workspace memberships) — #36 gap fix
     *                       (2026-08-15).
     */
    public static Specification<Employee> build(UUID tenantId, String search, String status, String department,
                                                 Collection<UUID> restrictToEmployeeIds,
                                                 Boolean faceRegistered, UUID workspaceId) {
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

            if (faceRegistered != null) {
                Subquery<UUID> enrolledSubquery = query.subquery(UUID.class);
                var faceRoot = enrolledSubquery.from(FaceProfile.class);
                enrolledSubquery.select(faceRoot.get("employeeId"))
                        .where(cb.equal(faceRoot.get("status"), "enrolled"));
                Predicate isEnrolled = root.get("id").in(enrolledSubquery);
                predicates.add(faceRegistered ? isEnrolled : cb.not(isEnrolled));
            }

            if (workspaceId != null) {
                Subquery<UUID> memberSubquery = query.subquery(UUID.class);
                var memberRoot = memberSubquery.from(WorkspaceMember.class);
                memberSubquery.select(memberRoot.get("employeeId"))
                        .where(cb.equal(memberRoot.get("workspaceId"), workspaceId),
                                cb.isNull(memberRoot.get("deletedAt")));
                predicates.add(root.get("id").in(memberSubquery));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
