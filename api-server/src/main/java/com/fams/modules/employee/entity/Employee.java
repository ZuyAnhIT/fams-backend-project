package com.fams.modules.employee.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 255)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(length = 100)
    private String position;

    @Column(length = 100)
    private String department;

    @Column(name = "department_id")
    private UUID departmentId;

    /** Intended role for this person once they're invited/linked to a login account — this
     *  Employee has no userId yet, so no real UserRole can exist. Carried forward automatically
     *  by EmployeeInvitationService#sendInvitation when an invite is later sent for this same
     *  email with no explicit roleId, instead of silently falling back to EMPLOYEE. */
    @Column(name = "planned_role_id")
    private UUID plannedRoleId;

    @Column(nullable = false, length = 20)
    private String status;

    /** When this employee's status last became "terminated" — cleared if HR reverses the
     *  decision (status moves away from terminated). Null if never terminated. #40 gap fix
     *  (2026-08-16): status alone couldn't answer "since when has this person been gone". */
    @Column(name = "terminated_at")
    private OffsetDateTime terminatedAt;

    /** National ID (CCCD/CMND) — same PII-masking convention as email/phone: stored plain,
     *  masked in API responses based on the caller's employees:pii:read permission (see
     *  PiiAccess). Not a separate at-rest-encryption mechanism, matching how email/phone are
     *  already handled in this codebase (#39 gap fix, 2026-08-16). */
    @Column(name = "national_id", length = 50)
    private String nationalId;

    @Column(name = "hired_date")
    private LocalDate hiredDate;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "active";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
