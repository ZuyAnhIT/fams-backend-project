package com.fams.modules.employee.repository;

import com.fams.modules.employee.entity.EmployeeInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeInvitationRepository extends JpaRepository<EmployeeInvitation, UUID>,
        JpaSpecificationExecutor<EmployeeInvitation> {

    @Query("""
            SELECT i FROM EmployeeInvitation i
            WHERE i.tenantId = :tenantId
              AND i.email = :email
              AND i.status = 'pending'
              AND i.deletedAt IS NULL
            """)
    Optional<EmployeeInvitation> findPendingByTenantAndEmail(
            @Param("tenantId") UUID tenantId,
            @Param("email") String email);

    @Query("""
            SELECT i FROM EmployeeInvitation i
            WHERE i.tenantId = :tenantId
              AND i.phone = :phone
              AND i.status = 'pending'
              AND i.deletedAt IS NULL
            """)
    Optional<EmployeeInvitation> findPendingByTenantAndPhone(
            @Param("tenantId") UUID tenantId,
            @Param("phone") String phone);

    @Query("""
            SELECT i FROM EmployeeInvitation i
            WHERE i.token = :token
              AND i.deletedAt IS NULL
            """)
    Optional<EmployeeInvitation> findByToken(@Param("token") UUID token);

    Optional<EmployeeInvitation> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
