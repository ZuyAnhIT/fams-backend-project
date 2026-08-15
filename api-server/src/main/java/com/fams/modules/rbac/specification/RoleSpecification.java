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

    public static Specification<Role> build(UUID tenantId, String search, Boolean isSystem, Boolean isActive,
                                            boolean includePlatformCustomRoles) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNull(root.get("deletedAt")));

            // Scope: system roles always included; tenant roles only if tenantId provided.
            // Platform-scoped custom roles (tenantId NULL, isSystem false) AND platform-tier
            // system roles (PLATFORM_ADMIN/PLATFORM_STAFF, isPlatformRole=true — see V91) are
            // FAMS's own internal governance structure — only surfaced to Platform Admins,
            // never to regular tenant users (applies whether or not a tenantId filter is also
            // given). Non-admin callers only ever see tenant-tier system roles here.
            Predicate globalRoles = includePlatformCustomRoles
                    ? cb.isNull(root.get("tenantId"))
                    : cb.and(cb.isNull(root.get("tenantId")), cb.isTrue(root.get("isSystem")),
                             cb.isFalse(root.get("isPlatformRole")));

            if (tenantId != null) {
                predicates.add(cb.or(globalRoles, cb.equal(root.get("tenantId"), tenantId)));
            } else {
                predicates.add(globalRoles);
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

            if (isActive != null) {
                predicates.add(cb.equal(root.get("isActive"), isActive));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
