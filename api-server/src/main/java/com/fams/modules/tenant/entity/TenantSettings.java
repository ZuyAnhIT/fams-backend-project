package com.fams.modules.tenant.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tenant_settings")
public class TenantSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "date_format", nullable = false, length = 30)
    private String dateFormat;

    @Column(name = "time_format", nullable = false, length = 20)
    private String timeFormat;

    @Column(name = "brand_primary_color", length = 7)
    private String brandPrimaryColor;

    @Column(name = "brand_secondary_color", length = 7)
    private String brandSecondaryColor;

    @Column(name = "brand_accent_color", length = 7)
    private String brandAccentColor;

    @Column(name = "employee_code_prefix", length = 20)
    private String employeeCodePrefix;

    @Column(name = "employee_code_padding", nullable = false)
    private short employeeCodePadding;

    @Column(name = "employee_code_seq", nullable = false)
    private long employeeCodeSeq;

    /** #144 (2026-08-19): null means "use the global default" (DataRetentionJob's @Value
     *  config) — a tenant can override how long their notifications/biometric photos are kept. */
    @Column(name = "data_retention_days")
    private Integer dataRetentionDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (dateFormat == null) dateFormat = "DD/MM/YYYY";
        if (timeFormat == null) timeFormat = "HH:mm";
        if (employeeCodePadding == 0) employeeCodePadding = 4;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
