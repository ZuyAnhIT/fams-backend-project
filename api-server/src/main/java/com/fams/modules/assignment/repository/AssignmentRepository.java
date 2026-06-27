package com.fams.modules.assignment.repository;

import com.fams.modules.assignment.entity.Assignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID>,
        JpaSpecificationExecutor<Assignment> {

    boolean existsByEmployeeIdAndSiteIdAndStatusAndDeletedAtIsNull(
            UUID employeeId, UUID siteId, String status);

    Optional<Assignment> findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
            UUID id, UUID siteId, UUID tenantId);

    Page<Assignment> findBySiteIdAndTenantIdAndDeletedAtIsNull(
            UUID siteId, UUID tenantId, Pageable pageable);

    long countBySiteIdAndStatusAndDeletedAtIsNull(UUID siteId, String status);
}
