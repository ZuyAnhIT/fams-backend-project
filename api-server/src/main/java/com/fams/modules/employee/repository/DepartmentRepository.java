package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findAllByTenantIdAndDeletedAtIsNullOrderByNameAsc(UUID tenantId);

    Optional<Department> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    @Query("""
        SELECT d FROM Department d
        WHERE d.tenantId = :tenantId
          AND lower(d.name) = lower(:name)
          AND d.deletedAt IS NULL
        """)
    Optional<Department> findByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
            @Param("tenantId") UUID tenantId, @Param("name") String name);
}
