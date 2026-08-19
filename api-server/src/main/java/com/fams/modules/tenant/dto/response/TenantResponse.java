package com.fams.modules.tenant.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Tenant (company workspace) details")
public class TenantResponse {

    @Schema(description = "Tenant UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Company display name", example = "Acme Corporation")
    private String name;

    @Schema(description = "URL-safe slug used in routing", example = "acme-corp")
    private String slug;

    @Schema(description = "Custom domain", example = "acme.example.com")
    private String domain;

    @Schema(description = "Company logo URL", example = "https://cdn.example.com/logos/acme.png")
    private String logoUrl;

    @Schema(description = "Industry sector", example = "Construction")
    private String industry;

    @Schema(description = "ISO-3166-1 alpha-2 country code", example = "VN")
    private String countryCode;

    @Schema(description = "IANA timezone identifier", example = "Asia/Ho_Chi_Minh")
    private String timezone;

    @Schema(description = "BCP-47 locale code", example = "vi-VN")
    private String locale;

    @Schema(description = "ISO-4217 currency code", example = "VND")
    private String currencyCode;

    @Schema(description = "Tenant status: ACTIVE, SUSPENDED, TRIAL, etc.", example = "ACTIVE")
    private String status;

    @Schema(description = "UUID of the user who owns / created this tenant")
    private UUID ownerId;

    @Schema(description = "Display name of the tenant owner", example = "Nguyen Van A")
    private String ownerName;

    @Schema(description = "Email of the tenant owner", example = "nguyenvana@example.com")
    private String ownerEmail;

    @Schema(description = "Subscribed plan name, null if the tenant has no subscription row yet (#16, 2026-08-19)", example = "pro")
    private String planName;

    @Schema(description = "Subscribed plan UUID, null if no subscription (#16, 2026-08-19)")
    private UUID planId;

    @Schema(description = "Subscription status: active, trial, expired, cancelled — null if no subscription (#16, 2026-08-19)", example = "active")
    private String subscriptionStatus;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
