package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Create a new tenant (company workspace)")
public class CreateTenantRequest {

    @Schema(description = "Company display name (2–255 chars)", example = "Acme Corporation")
    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @Schema(description = "URL-safe slug — lowercase letters, digits, and hyphens only (2–100 chars)", example = "acme-corp")
    @NotBlank(message = "Slug is required")
    @Size(min = 2, max = 100, message = "Slug must be between 2 and 100 characters")
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
             message = "Slug must contain only lowercase letters, digits, and hyphens")
    private String slug;

    @Schema(description = "Custom domain for the tenant (max 255 chars)", example = "acme.example.com")
    @Size(max = 255, message = "Domain must be at most 255 characters")
    private String domain;

    @Schema(description = "Industry sector (max 100 chars)", example = "Construction")
    @Size(max = 100, message = "Industry must be at most 100 characters")
    private String industry;

    @Schema(description = "ISO-3166-1 alpha-2 country code", example = "VN")
    @Size(min = 2, max = 2, message = "Country code must be exactly 2 characters")
    private String countryCode;

    @Schema(description = "IANA timezone identifier (max 100 chars)", example = "Asia/Ho_Chi_Minh")
    @Size(max = 100, message = "Timezone must be at most 100 characters")
    private String timezone;

    @Schema(description = "BCP-47 locale code (max 10 chars)", example = "vi-VN")
    @Size(max = 10, message = "Locale must be at most 10 characters")
    private String locale;

    @Schema(description = "ISO-4217 currency code", example = "VND")
    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters")
    private String currencyCode;

    @Schema(description = "Platform-provisioning only (requires the `tenants:create` permission — Platform "
            + "Admin or Platform Staff). The UUID of an EXISTING FAMS user to assign as this tenant's owner/"
            + "TENANT_ADMIN — that person must already have an account (this does not send an invitation or "
            + "create a user). Ignored (and must be omitted) for a self-service creation, where the caller "
            + "always becomes their own tenant's owner. Exactly one of ownerUserId/ownerEmail is required "
            + "when provisioning on behalf of someone else.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID ownerUserId;

    @Schema(description = "Platform-provisioning only — alternative to ownerUserId: look up the existing "
            + "owner by email instead of UUID. Returns 404 if no account with this email exists (this is "
            + "intentionally NOT an invitation — the person must already be a registered FAMS user).",
            example = "owner@example.com")
    @Email(message = "ownerEmail must be a valid email address")
    private String ownerEmail;
}
