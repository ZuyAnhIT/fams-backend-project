package com.fams.modules.randomcheck.repository;

import com.fams.modules.randomcheck.entity.CheckResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CheckResponseRepository extends JpaRepository<CheckResponse, UUID> {

    boolean existsByScheduledCheckId(UUID scheduledCheckId);

    Optional<CheckResponse> findByScheduledCheckId(UUID scheduledCheckId);
}
