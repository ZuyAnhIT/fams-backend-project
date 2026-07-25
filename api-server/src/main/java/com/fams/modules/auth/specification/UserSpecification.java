package com.fams.modules.auth.specification;

import com.fams.modules.auth.entity.User;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class UserSpecification {

    private UserSpecification() {}

    public static Specification<User> build(String search) {
        return build(search, null, null);
    }

    public static Specification<User> build(String search, Boolean isActive, Boolean isPlatformAdmin) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNull(root.get("deletedAt")));

            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("displayName")), like)
                ));
            }

            if (isActive != null) {
                predicates.add(cb.equal(root.get("isActive"), isActive));
            }

            if (isPlatformAdmin != null) {
                predicates.add(cb.equal(root.get("isPlatformAdmin"), isPlatformAdmin));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
