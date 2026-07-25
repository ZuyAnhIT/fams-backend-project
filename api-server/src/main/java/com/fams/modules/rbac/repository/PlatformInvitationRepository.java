package com.fams.modules.rbac.repository;

import com.fams.modules.rbac.entity.PlatformInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface PlatformInvitationRepository extends JpaRepository<PlatformInvitation, UUID>,
        JpaSpecificationExecutor<PlatformInvitation> {

    Optional<PlatformInvitation> findByToken(UUID token);

    Optional<PlatformInvitation> findByIdAndDeletedAtIsNull(UUID id);

    Optional<PlatformInvitation> findByEmailAndStatusAndDeletedAtIsNull(String email, String status);
}
