package com.fams.modules.tenant.dto.response;

import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Full operational view of a tenant: profile, subscription, plan limits, and current usage")
public class TenantDetailResponse {

    @Schema(description = "Tenant UUID")
    private UUID id;

    @Schema(description = "Tenant display name", example = "Acme Corp")
    private String name;

    @Schema(description = "URL-safe tenant identifier", example = "acme-corp")
    private String slug;

    @Schema(description = "Custom domain", example = "acme.example.com")
    private String domain;

    @Schema(description = "Logo URL")
    private String logoUrl;

    @Schema(description = "Industry", example = "construction")
    private String industry;

    @Schema(description = "ISO 3166-1 alpha-2 country code", example = "VN")
    private String countryCode;

    @Schema(description = "Tenant timezone", example = "Asia/Ho_Chi_Minh")
    private String timezone;

    @Schema(description = "Tenant locale", example = "vi")
    private String locale;

    @Schema(description = "Tenant status", example = "active")
    private String status;

    @Schema(description = "Tenant owner user UUID")
    private UUID ownerId;

    @Schema(description = "Tenant creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    // --- Subscription ---

    @Schema(description = "Internal plan key", example = "pro")
    private String planName;

    @Schema(description = "Plan display name", example = "Pro")
    private String planDisplayName;

    @Schema(description = "Subscription status", example = "ACTIVE")
    private SubscriptionStatus subscriptionStatus;

    @Schema(description = "Billing cycle", example = "MONTHLY")
    private BillingCycle billingCycle;

    @Schema(description = "Subscription start date (UTC)")
    private OffsetDateTime subscriptionStartedAt;

    @Schema(description = "Subscription expiry date (null = never expires)")
    private OffsetDateTime subscriptionExpiresAt;

    // --- Plan limits (null = unlimited) ---

    @Schema(description = "Maximum allowed employees (null = unlimited)", example = "500")
    private Integer maxEmployees;

    @Schema(description = "Maximum allowed sites (null = unlimited)", example = "50")
    private Integer maxSites;

    @Schema(description = "Maximum storage in GB (null = unlimited)", example = "100")
    private Integer maxStorageGb;

    @Schema(description = "Maximum random checks per month (null = unlimited)", example = "1000")
    private Integer maxRandomChecksPerMonth;

    // --- Current usage ---

    @Schema(description = "Current active employee count", example = "42")
    private long currentEmployeeCount;

    @Schema(description = "Current active site count", example = "3")
    private long currentSiteCount;

    @Schema(description = "Scheduled random checks issued this calendar month", example = "78")
    private long currentMonthRandomChecks;
}
