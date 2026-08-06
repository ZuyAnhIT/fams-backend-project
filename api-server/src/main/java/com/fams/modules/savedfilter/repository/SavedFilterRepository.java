package com.fams.modules.savedfilter.repository;

import com.fams.modules.savedfilter.entity.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {

    List<SavedFilter> findAllByTenantIdAndUserIdAndResourceTypeAndDeletedAtIsNullOrderByNameAsc(
            UUID tenantId, UUID userId, String resourceType);

    Optional<SavedFilter> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(UUID id, UUID tenantId, UUID userId);

    boolean existsByTenantIdAndUserIdAndResourceTypeAndNameIgnoreCaseAndDeletedAtIsNull(
            UUID tenantId, UUID userId, String resourceType, String name);

    Optional<SavedFilter> findByTenantIdAndUserIdAndResourceTypeAndIsDefaultTrueAndDeletedAtIsNull(
            UUID tenantId, UUID userId, String resourceType);
}
