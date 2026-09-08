package com.fams.modules.tenant.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fams.shared.time.VietnamTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(length = 255)
    private String domain;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(length = 100)
    private String industry;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(nullable = false, length = 100)
    private String timezone;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "pre_suspension_status", length = 20)
    private String preSuspensionStatus;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (timezone == null) {
            timezone = VietnamTime.ID;
        }
        if (locale == null) {
            locale = "en";
        }
        if (currencyCode == null) {
            currencyCode = "VND";
        }
        if (status == null) {
            status = "trial";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
