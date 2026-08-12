package com.fams.modules.randomcheck.repository;

import com.fams.modules.randomcheck.entity.CheckResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckResponseRepository extends JpaRepository<CheckResponse, UUID> {

    boolean existsByScheduledCheckId(UUID scheduledCheckId);

    Optional<CheckResponse> findByScheduledCheckId(UUID scheduledCheckId);

    /** Batch variant of findByScheduledCheckId — used to hydrate outcome/failureReason onto a
     *  page of scheduled checks without an N+1 query per row (found via FE audit, 2026-07-31). */
    List<CheckResponse> findAllByScheduledCheckIdIn(List<UUID> scheduledCheckIds);

    /** Used by FaceVerifyTimeoutService (2026-08-12 backend readiness assessment): a photo was
     *  submitted for async face/liveness verification (photoSubmitted=true) but faceVerified is
     *  still NULL — the fams-ai callback never arrived — and enough time has passed that this is
     *  no longer "still processing", it's genuinely stuck (AI service down/crashed/lost the job). */
    List<CheckResponse> findByPhotoSubmittedTrueAndFaceVerifiedIsNullAndRespondedAtBefore(
            OffsetDateTime cutoff);
}
