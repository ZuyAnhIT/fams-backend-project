package com.fams.modules.audit.repository;

import com.fams.modules.audit.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AuditLogSpecification {

  private AuditLogSpecification() {}

  public static Specification<AuditLog> withFilters(
      UUID tenantId,
      UUID actorId,
      String entityType,
      String entityId,
      String action,
      OffsetDateTime from,
      OffsetDateTime to) {

    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();

      if (tenantId != null) {
        predicates.add(cb.equal(root.get("tenantId"), tenantId));
      }
      if (actorId != null) {
        predicates.add(cb.equal(root.get("actorId"), actorId));
      }
      if (entityType != null && !entityType.isBlank()) {
        predicates.add(cb.equal(root.get("entityType"), entityType));
      }
      if (entityId != null && !entityId.isBlank()) {
        predicates.add(cb.equal(root.get("entityId"), entityId));
      }
      if (action != null && !action.isBlank()) {
        predicates.add(cb.equal(root.get("action"), action));
      }
      if (from != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      }
      if (to != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
      }

      if (query != null) {
        query.orderBy(cb.desc(root.get("createdAt")));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
