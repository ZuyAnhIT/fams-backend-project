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
        summary = "Search users",
        description = "Returns a paginated list of users matching the search query (email or display name). " +
                      "Restricted to Platform Admins. Useful for looking up a userId before assigning a tenant role."
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
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("Search users: search={} page={} size={}", search, page, size);
        PageResponse<UserProfileResponse> response = userProfileService.searchUsers(search, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
