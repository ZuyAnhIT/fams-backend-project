package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.FaceVerifyRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FaceVerifyRequestRepository extends JpaRepository<FaceVerifyRequest, UUID> {

    Optional<FaceVerifyRequest> findByIdAndTenantIdAndEmployeeId(UUID id, UUID tenantId, UUID employeeId);
}
