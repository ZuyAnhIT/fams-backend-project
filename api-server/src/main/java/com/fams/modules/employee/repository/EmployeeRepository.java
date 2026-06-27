package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    boolean existsByTenantIdAndEmployeeCodeAndDeletedAtIsNull(UUID tenantId, String employeeCode);

    boolean existsByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);

    Optional<Employee> findByUserIdAndTenantIdAndDeletedAtIsNull(UUID userId, UUID tenantId);

    boolean existsByTenantIdAndEmployeeCodeAndDeletedAtIsNullAndIdNot(UUID tenantId, String employeeCode, UUID id);
}
