package com.fams.modules.subscription.repository;

import com.fams.modules.subscription.entity.PlanLimits;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanLimitsRepository extends JpaRepository<PlanLimits, UUID> {

    Optional<PlanLimits> findByPlanId(UUID planId);
}
