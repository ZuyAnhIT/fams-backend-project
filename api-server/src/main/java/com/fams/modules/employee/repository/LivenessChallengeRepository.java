package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.LivenessChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface LivenessChallengeRepository extends JpaRepository<LivenessChallenge, UUID> {

    Optional<LivenessChallenge> findByIdAndTenantIdAndEmployeeId(UUID id, UUID tenantId, UUID employeeId);

    /** Atomic claim — only succeeds (returns 1) if the challenge is still 'passed' at the moment
     *  of the update, so two concurrent requests replaying the same challengeId can't both
     *  consume it (the loser sees 0 rows affected). Bulk updates bypass the persistence context,
     *  so callers must not rely on an already-loaded LivenessChallenge instance's status field
     *  being accurate after calling this. */
    @Modifying
    @Query("UPDATE LivenessChallenge c SET c.status = 'consumed', c.consumedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.status = 'passed'")
    int consumeIfPassed(@Param("id") UUID id);

    /** Rate-limiting: how many challenges this employee has started recently, regardless of
     *  outcome — caps abuse (repeated attempts to brute-force liveness) independent of whether
     *  earlier attempts passed or failed. */
    long countByTenantIdAndEmployeeIdAndCreatedAtAfter(UUID tenantId, UUID employeeId, OffsetDateTime after);
}
