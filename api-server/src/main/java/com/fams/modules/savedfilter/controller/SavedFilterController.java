package com.fams.modules.savedfilter.controller;

import com.fams.modules.savedfilter.dto.request.CreateSavedFilterRequest;
import com.fams.modules.savedfilter.dto.request.UpdateSavedFilterRequest;
import com.fams.modules.savedfilter.dto.response.SavedFilterResponse;
import com.fams.modules.savedfilter.service.SavedFilterService;
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
@Tag(name = "Saved Filters", description = "Personal saved filters for large list screens (violations, checkins, employees, reports...)")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/saved-filters")
public class SavedFilterController {

    private final SavedFilterService savedFilterService;

    public SavedFilterController(SavedFilterService savedFilterService) {
        this.savedFilterService = savedFilterService;
    }

    @Operation(
        summary = "List my saved filters for a resource type",
        description = "Returns the authenticated user's own saved filters for the given resourceType "
                + "(e.g. 'violations', 'checkins'), sorted by name. No permission required beyond a "
                + "valid session — self-scoped by construction, same as every other 'my *' endpoint.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "List of the caller's saved filters for this resource type"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<SavedFilterResponse>>> list(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Which list screen, e.g. violations, checkins, employees", required = true)
                @RequestParam String resourceType,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        List<SavedFilterResponse> result = savedFilterService.list(tenantId, userDetails.getUserId(), resourceType);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Save a new filter",
        description = "Creates a named saved filter for the authenticated user. `filterParams` is stored "
                + "and returned verbatim — the backend never interprets its shape, the frontend re-applies "
                + "it as-is to the corresponding list endpoint's query params. Returns 409 if the name is "
                + "already used for this resourceType by this user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Saved filter created",
            content = @Content(schema = @Schema(implementation = SavedFilterResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — resourceType/name/filterParams required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "A saved filter with this name already exists for this resourceType")
    })
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SavedFilterResponse>> create(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateSavedFilterRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        SavedFilterResponse response = savedFilterService.create(tenantId, userDetails.getUserId(), request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update a saved filter",
        description = "Renames, updates the stored params, and/or changes default status. Only the "
                + "fields sent are changed. Returns 404 if the filter doesn't exist or isn't owned by "
                + "the caller.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Saved filter updated",
            content = @Content(schema = @Schema(implementation = SavedFilterResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Saved filter not found or not owned by the caller"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "New name already used for this resourceType")
    })
    @PatchMapping("/{filterId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SavedFilterResponse>> update(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Saved filter UUID") @PathVariable UUID filterId,
            @RequestBody UpdateSavedFilterRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        SavedFilterResponse response = savedFilterService.update(tenantId, userDetails.getUserId(), filterId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Delete a saved filter",
        description = "Soft-deletes the given saved filter. Returns 404 if it doesn't exist or isn't "
                + "owned by the caller.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "Saved filter deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Saved filter not found or not owned by the caller")
    })
    @DeleteMapping("/{filterId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Saved filter UUID") @PathVariable UUID filterId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        savedFilterService.delete(tenantId, userDetails.getUserId(), filterId);
        return ResponseEntity.noContent().build();
    }
}
