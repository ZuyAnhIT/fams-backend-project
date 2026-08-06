package com.fams.modules.notification.specification;

import com.fams.modules.notification.entity.NotificationDeliveryLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class NotificationDeliveryLogSpecification {

    private NotificationDeliveryLogSpecification() {}

    public static Specification<NotificationDeliveryLog> build(String status, String channel,
                                                                 OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(channel)) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
