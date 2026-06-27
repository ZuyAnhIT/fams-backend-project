package com.fams.modules.shift.repository;

import com.fams.modules.shift.entity.Shift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    boolean existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNull(UUID siteId, String name);

    boolean existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(UUID siteId, String name, UUID id);

    Optional<Shift> findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID siteId, UUID tenantId);

    Page<Shift> findBySiteIdAndTenantIdAndDeletedAtIsNull(UUID siteId, UUID tenantId, Pageable pageable);

    Page<Shift> findBySiteIdAndTenantIdAndStatusAndDeletedAtIsNull(UUID siteId, UUID tenantId, String status, Pageable pageable);

    List<Shift> findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(UUID siteId, String status);
}
