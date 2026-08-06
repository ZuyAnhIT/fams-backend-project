package com.fams.modules.savedfilter.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saved_filters")
public class SavedFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Which list screen this filter applies to, e.g. "violations", "checkins", "employees" —
     *  opaque string, not an enum, so a new list screen can adopt saved filters without a
     *  migration; the frontend owns the set of valid values it actually offers a "save filter"
     *  button on. */
    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** The exact query params the frontend re-applies verbatim to the list endpoint — backend
     *  never interprets its shape. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_params", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> filterParams;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

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
        if (filterParams == null) filterParams = Map.of();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
