package com.fams.modules.search.controller;

import com.fams.modules.search.dto.response.GlobalSearchResponse;
import com.fams.modules.search.service.SearchService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Search", description = "Global quick-search across employees, sites, and check-ins")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
        summary = "Global quick search (HR/Admin)",
        description = "Searches employees (by name, email, employee code, position, department), " +
                      "sites (by name, code, address, description), and returns recent check-ins for " +
                      "matched employees — all in a single request. The query must be at least 2 characters. " +
                      "Results are capped at `limit` per category (default 5, max 20). " +
                      "Requires employees:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Search results returned successfully",
            content = @Content(schema = @Schema(implementation = GlobalSearchResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Missing or blank query parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — employees:list permission required")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<GlobalSearchResponse>> search(
            @PathVariable UUID tenantId,
            @Parameter(description = "Search query (min 2 characters)", required = true)
                @RequestParam String q,
            @Parameter(description = "Max results per category (1–20, default 5)")
                @RequestParam(defaultValue = "5") int limit,
            @AuthenticationPrincipal FamsUserDetails caller) {

        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("Search query 'q' must not be blank");
        }
        limit = Math.min(Math.max(limit, 1), 20);

        GlobalSearchResponse result = searchService.search(
                tenantId, q, limit,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
