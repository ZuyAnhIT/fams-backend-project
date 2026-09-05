package com.fams.modules.subscription.specification;

import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class BillingOrderSpecification {

    private BillingOrderSpecification() {
    }

    public static Specification<BillingOrder> build(UUID tenantId, BillingOrderStatus status) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (tenantId != null) predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
