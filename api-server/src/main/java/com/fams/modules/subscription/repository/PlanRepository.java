package com.fams.modules.subscription.repository;

import com.fams.modules.subscription.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    List<Plan> findByDeletedAtIsNullOrderBySortOrderAsc();

    List<Plan> findByIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc();

    Optional<Plan> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Plan> findByNameAndDeletedAtIsNull(String name);
}
