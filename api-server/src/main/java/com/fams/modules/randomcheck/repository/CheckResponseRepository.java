package com.fams.modules.randomcheck.repository;

import com.fams.modules.randomcheck.entity.CheckResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckResponseRepository extends JpaRepository<CheckResponse, UUID> {

    boolean existsByScheduledCheckId(UUID scheduledCheckId);

    Optional<CheckResponse> findByScheduledCheckId(UUID scheduledCheckId);

    /** Batch variant of findByScheduledCheckId — used to hydrate outcome/failureReason onto a
     *  page of scheduled checks without an N+1 query per row (found via FE audit, 2026-07-31). */
    List<CheckResponse> findAllByScheduledCheckIdIn(List<UUID> scheduledCheckIds);
}
