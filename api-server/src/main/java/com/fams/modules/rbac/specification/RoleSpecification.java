package com.fams.modules.rbac.specification;

import com.fams.modules.rbac.entity.Role;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RoleSpecification {

    private RoleSpecification() {}

    public static Specification<Role> build(UUID tenantId, String search, Boolean isSystem) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNull(root.get("deletedAt")));

            // Scope: system roles always included; tenant roles only if tenantId provided
            if (tenantId != null) {
                predicates.add(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId)
                ));
            } else {
                predicates.add(cb.isNull(root.get("tenantId")));
            }

            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(
                        cb.lower(root.get("name")),
                        "%" + search.toLowerCase() + "%"
                ));
            }

            if (isSystem != null) {
                predicates.add(cb.equal(root.get("isSystem"), isSystem));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
