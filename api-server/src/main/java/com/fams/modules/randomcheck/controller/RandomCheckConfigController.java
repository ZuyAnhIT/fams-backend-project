package com.fams.modules.randomcheck.controller;

import com.fams.modules.randomcheck.dto.request.CreateRandomCheckConfigRequest;
import com.fams.modules.randomcheck.dto.request.UpdateApplicableRolesRequest;
import com.fams.modules.randomcheck.dto.request.UpdateCheckModeRequest;
import com.fams.modules.randomcheck.dto.request.UpdateRandomCheckConfigRequest;
import com.fams.modules.randomcheck.dto.response.RandomCheckConfigResponse;
import com.fams.modules.randomcheck.service.RandomCheckConfigService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Random Check Config", description = "Manage random check configurations (tenant default and site overrides)")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/random-check-configs")
public class RandomCheckConfigController {

    private final RandomCheckConfigService configService;

    public RandomCheckConfigController(RandomCheckConfigService configService) {
        this.configService = configService;
    }

    @Operation(
        summary = "Create site-level random check configuration override",
        description = "Creates a site-specific random check policy that overrides the tenant default for the given site. " +
                      "Only one override may exist per site; returns 409 if one already exists. " +
                      "Returns 404 if the site does not belong to this tenant. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Site configuration created successfully",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — missing or invalid fields"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Site not found in this tenant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Site configuration already exists — use PUT /{configId} to update")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/sites/{siteId}")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> createSiteOverride(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @Valid @RequestBody CreateRandomCheckConfigRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create site random check config tenantId={} siteId={} userId={}", tenantId, siteId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.createSiteOverride(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get site-level random check configuration override",
        description = "Returns the site-specific random check policy for the given site, if one exists. " +
                      "Returns 404 if no override has been created for this site, or if the site does not exist. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Site configuration returned",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Site not found or no override configuration exists for this site")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping("/sites/{siteId}")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> getSiteOverride(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get site random check config tenantId={} siteId={} userId={}", tenantId, siteId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.getSiteOverride(
                tenantId, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get the effective (resolved) random check configuration for a site",
        description = "Returns whichever config actually applies to this site right now — the site override "
                      + "if one exists and is active, otherwise the tenant default — the exact same resolution "
                      + "order the daily generator and manual-check dispatch use. Unlike GET /sites/{siteId}, "
                      + "this never 404s just because there's no site-specific override (it falls through to "
                      + "the tenant default); it only 404s if NEITHER exists. The response's resolvedFrom field "
                      + "says which one applied: 'site_override' or 'tenant_default'. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Effective configuration returned",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Site not found, or neither a site override nor a tenant default configuration exists")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping("/sites/{siteId}/effective")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> getEffectiveConfig(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get effective random check config tenantId={} siteId={} userId={}", tenantId, siteId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.getEffectiveConfig(
                tenantId, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Create tenant-default random check configuration",
        description = "Creates the company-wide default random check policy for the tenant. " +
                      "Only one tenant-default config may exist; returns 409 if one already exists. " +
                      "Defines check frequency, allowed time window, verification mode, applicable roles, " +
                      "and employee response window. Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Configuration created successfully",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — missing or invalid fields"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Tenant-default configuration already exists — use PUT to update")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/tenant-default")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> createTenantDefault(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateRandomCheckConfigRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create tenant-default random check config tenantId={} userId={}", tenantId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.createTenantDefault(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get tenant-default random check configuration",
        description = "Returns the company-wide default random check policy. " +
                      "Returns 404 if no default has been configured yet. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Tenant-default configuration returned",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No default configuration found for this tenant")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping("/tenant-default")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> getTenantDefault(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get tenant-default random check config tenantId={} userId={}", tenantId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.getTenantDefault(
                tenantId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "List all random check configurations",
        description = "Returns all random check configurations for the tenant, including the tenant-default " +
                      "and any site-level overrides. Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "List of configurations returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RandomCheckConfigResponse>>> listConfigs(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List random check configs tenantId={} userId={}", tenantId, userDetails.getUserId());
        List<RandomCheckConfigResponse> configs = configService.listConfigs(
                tenantId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @Operation(
        summary = "Get a specific random check configuration",
        description = "Returns a single random check configuration by ID. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Configuration returned",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Configuration not found")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping("/{configId}")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> getConfig(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Config UUID") @PathVariable UUID configId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get random check config tenantId={} configId={} userId={}", tenantId, configId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.getConfig(
                tenantId, configId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update a random check configuration",
        description = "Partially updates an existing random check configuration. " +
                      "Only provided fields are updated. Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Configuration updated successfully",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Configuration not found")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PutMapping("/{configId}")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> updateConfig(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Config UUID") @PathVariable UUID configId,
            @Valid @RequestBody UpdateRandomCheckConfigRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update random check config tenantId={} configId={} userId={}", tenantId, configId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.updateConfig(
                tenantId, configId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update the applicable roles for a configuration",
        description = "Replaces the list of site roles that are subject to random checks. " +
                      "Only employees whose Assignment.role matches one of the listed roles will receive checks — " +
                      "currently only 'worker' and 'supervisor' are ever possible values for Assignment.role, " +
                      "so those are the only meaningful entries here. " +
                      "Pass an empty list to apply checks to all roles regardless of their site role. " +
                      "Does not affect any other configuration fields. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Applicable roles updated successfully",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — null list or blank role name"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Configuration not found")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PutMapping("/{configId}/applicable-roles")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> updateApplicableRoles(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Config UUID") @PathVariable UUID configId,
            @Valid @RequestBody UpdateApplicableRolesRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update applicable_roles tenantId={} configId={} userId={}", tenantId, configId, userDetails.getUserId());
        RandomCheckConfigResponse response = configService.updateApplicableRoles(
                tenantId, configId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update the check mode for a configuration",
        description = "Changes only the verification mode (location_only / location_face / location_face_liveness) " +
                      "without affecting any other scheduling fields. " +
                      "location_only: GPS presence only. " +
                      "location_face: GPS + selfie face match (face verification handled by AI service). " +
                      "location_face_liveness: GPS + face + liveness detection (handled by AI service). " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check mode updated successfully",
            content = @Content(schema = @Schema(implementation = RandomCheckConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid check_mode value"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Configuration not found")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PutMapping("/{configId}/check-mode")
    public ResponseEntity<ApiResponse<RandomCheckConfigResponse>> updateCheckMode(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Config UUID") @PathVariable UUID configId,
            @Valid @RequestBody UpdateCheckModeRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update check_mode tenantId={} configId={} mode={} userId={}",
                tenantId, configId, request.getCheckMode(), userDetails.getUserId());
        RandomCheckConfigResponse response = configService.updateCheckMode(
                tenantId, configId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Delete a random check configuration",
        description = "Soft-deletes a random check configuration. " +
                      "Scheduled checks that were generated from this config are not affected. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "Configuration deleted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Configuration not found")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @DeleteMapping("/{configId}")
    public ResponseEntity<Void> deleteConfig(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Config UUID") @PathVariable UUID configId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete random check config tenantId={} configId={} userId={}", tenantId, configId, userDetails.getUserId());
        configService.deleteConfig(tenantId, configId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.noContent().build();
    }
}
