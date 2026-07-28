package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.FaceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceProfileRepository extends JpaRepository<FaceProfile, UUID> {

    Optional<FaceProfile> findByEmployeeIdAndTenantId(UUID employeeId, UUID tenantId);

    List<FaceProfile> findAllByEmployeeIdInAndTenantId(List<UUID> employeeIds, UUID tenantId);

    List<FaceProfile> findAllByTenantId(UUID tenantId);

    List<FaceProfile> findAllByRevokedAtIsNotNullAndEmbeddingDeletedFalse();

    /** HR review queue — profiles with a submitted-but-not-yet-decided enrollment batch. */
    List<FaceProfile> findAllByTenantIdAndReviewStatus(UUID tenantId, String reviewStatus);
}
