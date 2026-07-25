package com.fams.modules.auth.controller;

import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.service.UserProfileService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Users", description = "Platform-level user search and lookup")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Operation(
        summary = "Search users (platform-wide directory)",
        description = "Returns a paginated, searchable, sortable, filterable list of every user account on the "
            + "platform. Restricted to Platform Admins. Two primary uses: (1) autocomplete/lookup for the "
            + "ownerUserId field when provisioning a tenant on a customer's behalf (POST /tenants), and (2) "
            + "browsing/managing FAMS's own internal staff before assigning a platform-scoped role "
            + "(POST /user-roles/platform) — filter isPlatformAdmin=true to see just the current platform team."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required")
    })
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserProfileResponse>>> searchUsers(
            @Parameter(description = "Search by email or display name") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by account active/inactive status") @RequestParam(required = false) Boolean isActive,
            @Parameter(description = "Filter to platform-admin accounts only (true) or non-admin accounts only (false)") @RequestParam(required = false) Boolean isPlatformAdmin,
            @Parameter(description = "Sort field: email, displayName, createdAt, lastLoginAt") @RequestParam(defaultValue = "email") String sortBy,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "asc") String sortDir,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("Search users: search={} isActive={} isPlatformAdmin={} sortBy={} page={} size={}",
                search, isActive, isPlatformAdmin, sortBy, page, size);
        PageResponse<UserProfileResponse> response =
                userProfileService.searchUsers(search, isActive, isPlatformAdmin, sortBy, sortDir, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
