package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    boolean existsByTenantIdAndEmployeeCodeAndDeletedAtIsNull(UUID tenantId, String employeeCode);

    boolean existsByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);

    Optional<Employee> findByUserIdAndTenantIdAndDeletedAtIsNull(UUID userId, UUID tenantId);

    boolean existsByTenantIdAndEmployeeCodeAndDeletedAtIsNullAndIdNot(UUID tenantId, String employeeCode, UUID id);

    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.tenantId = :tenantId AND e.deletedAt IS NULL AND e.createdAt >= :since")
    long countNewSince(@Param("tenantId") UUID tenantId, @Param("since") OffsetDateTime since);

    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId AND e.id IN :ids AND e.deletedAt IS NULL")
    List<Employee> findAllByTenantIdAndIdInAndDeletedAtIsNull(
            @Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);
}
