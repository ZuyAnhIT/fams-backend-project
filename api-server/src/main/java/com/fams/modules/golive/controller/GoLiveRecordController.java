package com.fams.modules.golive.controller;

import com.fams.modules.golive.dto.request.ApproveGoLiveRecordRequest;
import com.fams.modules.golive.dto.request.CreateGoLiveRecordRequest;
import com.fams.modules.golive.dto.request.UpdateGoLiveStepsRequest;
import com.fams.modules.golive.dto.response.GoLiveRecordResponse;
import com.fams.modules.golive.service.GoLiveRecordService;
import com.fams.shared.pagination.PageResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Go-live Records", description = "Formal go-live checklist sign-off records — one per tenant go-live attempt")
@RestController
@RequestMapping("/api/v1/platform/go-live-records")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('golive:manage')")
public class GoLiveRecordController {

    private final GoLiveRecordService service;

    public GoLiveRecordController(GoLiveRecordService service) {
        this.service = service;
    }

    @Operation(
        summary = "Create a new go-live checklist run",
        description = "Starts a formal go-live record for a tenant — persists tenant, environment, build " +
                      "version, tester (the caller), and start time. Steps may be submitted here or added " +
                      "incrementally via PATCH .../steps. Cross-tenant by nature (the deployment team member " +
                      "isn't necessarily a member of the tenant going live) — PLATFORM_ADMIN only, matching " +
                      "docs/deployment/go-live-checklist.md."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Record created",
            content = @Content(schema = @Schema(implementation = GoLiveRecordResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<GoLiveRecordResponse>> create(
            @Valid @RequestBody CreateGoLiveRecordRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        GoLiveRecordResponse response = service.create(request, userDetails.getUserId());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "List go-live records", description = "Filterable by tenantId and/or status (DRAFT/APPROVED/REJECTED).")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GoLiveRecordResponse>>> list(
            @Parameter(description = "Filter by tenant UUID") @RequestParam(required = false) UUID tenantId,
            @Parameter(description = "Filter by status: DRAFT, APPROVED, REJECTED") @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<GoLiveRecordResponse> response = service.list(tenantId, status, PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Get a go-live record")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GoLiveRecordResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.getById(id)));
    }

    @Operation(
        summary = "Replace the checklist step results on a DRAFT record",
        description = "409 if the record has already been approved/rejected — a signed-off record is immutable."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already approved/rejected")
    })
    @PatchMapping("/{id}/steps")
    public ResponseEntity<ApiResponse<GoLiveRecordResponse>> updateSteps(
            @PathVariable UUID id, @Valid @RequestBody UpdateGoLiveStepsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateSteps(id, request)));
    }

    @Operation(
        summary = "Approve a go-live record",
        description = "Signs off the record — records the approver (the caller), timestamp, and an optional " +
                      "note. Terminal: a record can only be approved/rejected once."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already decided")
    })
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<GoLiveRecordResponse>> approve(
            @PathVariable UUID id, @RequestBody(required = false) ApproveGoLiveRecordRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(service.approve(id, request, userDetails.getUserId())));
    }

    @Operation(
        summary = "Reject a go-live record",
        description = "Terminal: a record can only be approved/rejected once. Create a new record for a re-run."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rejected"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already decided")
    })
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<GoLiveRecordResponse>> reject(
            @PathVariable UUID id, @RequestBody(required = false) ApproveGoLiveRecordRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(service.reject(id, request, userDetails.getUserId())));
    }
}
