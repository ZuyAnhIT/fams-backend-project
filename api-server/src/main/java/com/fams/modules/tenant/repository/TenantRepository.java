package com.fams.modules.tenant.repository;

import com.fams.modules.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID>, JpaSpecificationExecutor<Tenant> {

    Optional<Tenant> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Tenant> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Tenant> findByDomainAndDeletedAtIsNull(String domain);

    long countByStatusAndDeletedAtIsNull(String status);

    List<Tenant> findAllByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    /** Used to self-heal a tenant owner's missing role assignment (see
     *  UserRoleService#selfHealOwnerRoles) — ownership is a separate, permanent relationship
     *  from role assignments, so we need to find every tenant a user owns regardless of
     *  whether they currently hold any active role in it. */
    List<Tenant> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId);
}
