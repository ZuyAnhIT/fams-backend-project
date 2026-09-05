package com.fams.modules.shift.repository;

import com.fams.modules.shift.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftRepository extends JpaRepository<Shift, UUID>, JpaSpecificationExecutor<Shift> {

    boolean existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNull(UUID siteId, String name);

    boolean existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(UUID siteId, String name, UUID id);

    Optional<Shift> findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID siteId, UUID tenantId);

    Optional<Shift> findBySiteIdAndIsDefaultTrueAndDeletedAtIsNull(UUID siteId);

    List<Shift> findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(UUID siteId, String status);

    List<Shift> findByTenantIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(UUID tenantId, String status);
}
