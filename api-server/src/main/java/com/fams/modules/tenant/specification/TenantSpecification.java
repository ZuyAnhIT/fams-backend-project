package com.fams.modules.tenant.specification;

import com.fams.modules.tenant.entity.Tenant;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class TenantSpecification {

    private TenantSpecification() {}

    public static Specification<Tenant> build(String search, String status, String industry, String countryCode) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNull(root.get("deletedAt")));

            if (StringUtils.hasText(search)) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("slug")), like),
                        cb.like(cb.lower(root.get("domain")), like)
                ));
            }

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (StringUtils.hasText(industry)) {
                predicates.add(cb.equal(cb.lower(root.get("industry")), industry.toLowerCase()));
            }

            if (StringUtils.hasText(countryCode)) {
                predicates.add(cb.equal(cb.lower(root.get("countryCode")), countryCode.toLowerCase()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
