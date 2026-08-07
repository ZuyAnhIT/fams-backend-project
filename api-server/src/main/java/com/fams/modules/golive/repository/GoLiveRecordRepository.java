package com.fams.modules.golive.repository;

import com.fams.modules.golive.entity.GoLiveRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GoLiveRecordRepository extends JpaRepository<GoLiveRecord, UUID> {

    Optional<GoLiveRecord> findByIdAndDeletedAtIsNull(UUID id);

    Page<GoLiveRecord> findAllByDeletedAtIsNullAndTenantId(UUID tenantId, Pageable pageable);

    Page<GoLiveRecord> findAllByDeletedAtIsNullAndStatus(String status, Pageable pageable);

    Page<GoLiveRecord> findAllByDeletedAtIsNullAndTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    Page<GoLiveRecord> findAllByDeletedAtIsNull(Pageable pageable);
}
