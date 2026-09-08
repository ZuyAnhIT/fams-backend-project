package com.fams.modules.subscription.specification;

import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

public final class BillingOrderSpecification {

    private BillingOrderSpecification() {
    }

    public static Specification<BillingOrder> build(String search, UUID tenantId,
                                                     BillingOrderStatus status, BillingCycle billingCycle) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (tenantId != null) predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (billingCycle != null) predicates.add(cb.equal(root.get("billingCycle"), billingCycle));
            if (search != null && !search.isBlank()) {
                String normalized = search.trim().toLowerCase(Locale.ROOT);
                String pattern = "%" + normalized + "%";
                var searchable = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
                searchable.add(cb.like(cb.lower(root.get("tenantNameSnapshot")), pattern));
                searchable.add(cb.like(cb.lower(root.get("planDisplaySnapshot")), pattern));
                searchable.add(cb.like(cb.lower(root.get("planNameSnapshot")), pattern));
                searchable.add(cb.like(cb.lower(cb.coalesce(root.get("paymentReference"), "")), pattern));
                String orderCode = normalized.startsWith("#") ? normalized.substring(1) : normalized;
                try {
                    searchable.add(cb.equal(root.get("orderCode"), Long.parseLong(orderCode)));
                } catch (NumberFormatException ignored) {
                    // A free-text query is still valid for the human-readable fields above.
                }
                predicates.add(cb.or(searchable.toArray(jakarta.persistence.criteria.Predicate[]::new)));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
