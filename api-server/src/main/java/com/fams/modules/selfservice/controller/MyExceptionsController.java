package com.fams.modules.selfservice.controller;

import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.checkin.service.CheckinService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.selfservice.dto.response.MyExceptionItemResponse;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.modules.violation.service.ViolationService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Merges the two employee-facing "needs my explanation" inboxes — pending_review check-ins and
 * unresolved violations — into one list. The two underlying records stay genuinely separate
 * (different triggers, different explain endpoints: POST .../checkin/{id}/explain vs
 * POST .../violations/{id}/explain — audit 2026-08-03 deliberately kept them that way), this
 * controller only unifies the READ side so a single screen can show "everything that needs your
 * attention today" instead of the employee having to check two different lists.
 */
@Slf4j
@Tag(name = "Self-Service", description = "Employee self-scoped aggregated views")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/me/exceptions")
public class MyExceptionsController {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 100;

    private final CheckinService checkinService;
    private final ViolationService violationService;
    private final EmployeeRepository employeeRepository;

    public MyExceptionsController(CheckinService checkinService, ViolationService violationService,
                                  EmployeeRepository employeeRepository) {
        this.checkinService = checkinService;
        this.violationService = violationService;
        this.employeeRepository = employeeRepository;
    }

    @Operation(
        summary = "Employee's merged 'needs my explanation' inbox",
        description = "Combines the employee's own pending_review check-ins and unresolved violations "
                + "into a single list, sorted newest-first, so a single screen can drive both explanation "
                + "forms instead of the employee checking two separate lists."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<List<MyExceptionItemResponse>>> listMyExceptions(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Max combined items to return (default 50, max 100)")
                @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        int cappedSize = Math.min(size, MAX_SIZE);
        UUID userId = userDetails.getUserId();

        // "Cần giải thích" is in the customer nav for every tenant member (tenant_admin,
        // hr_manager, supervisor, employee) — but only accounts with an actual employee profile
        // can have pending_review check-ins or violations. A pure tenant_admin / HR account with
        // no profile genuinely has nothing to explain: return an empty inbox (200), not the 404
        // that the underlying getCheckinHistory / listMyViolations throw on a missing profile
        // (which surfaced as a dead-end red "Không thể tải hộp thư" error on Web + App — #19).
        if (!employeeRepository.existsByTenantIdAndUserIdAndDeletedAtIsNull(tenantId, userId)) {
            log.info("Merged exceptions inbox: tenantId={} userId={} has no employee profile — empty inbox",
                    tenantId, userId);
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        List<CheckinResponse> pendingCheckins = checkinService
                .getCheckinHistory(tenantId, userId, null, "pending_review", null, null, 0, cappedSize)
                .getContent();
        List<ViolationListResponse> unresolvedViolations = violationService
                .listMyViolations(tenantId, userId, false, 0, cappedSize)
                .getContent();

        List<MyExceptionItemResponse> merged = new ArrayList<>(
                pendingCheckins.size() + unresolvedViolations.size());

        for (CheckinResponse c : pendingCheckins) {
            merged.add(MyExceptionItemResponse.builder()
                    .id(c.getId())
                    .sourceType("checkin")
                    .reasonType("pending_review")
                    .date(c.getCheckInAt() == null ? null : c.getCheckInAt().toLocalDate())
                    .description(c.getMessage())
                    .explainEndpoint("/api/v1/tenants/" + tenantId + "/checkin/" + c.getId() + "/explain")
                    .hasExplanation(StringUtils.hasText(c.getEmployeeNote()))
                    .employeeNote(c.getEmployeeNote())
                    .createdAt(c.getCreatedAt())
                    .build());
        }
        for (ViolationListResponse v : unresolvedViolations) {
            merged.add(MyExceptionItemResponse.builder()
                    .id(v.getId())
                    .sourceType("violation")
                    .reasonType(v.getViolationType())
                    .date(v.getCheckDate())
                    .description(v.getDescription())
                    .explainEndpoint("/api/v1/tenants/" + tenantId + "/violations/" + v.getId() + "/explain")
                    .hasExplanation(StringUtils.hasText(v.getEmployeeNote()))
                    .employeeNote(v.getEmployeeNote())
                    .createdAt(v.getCreatedAt())
                    .build());
        }

        merged.sort(Comparator.comparing(MyExceptionItemResponse::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (merged.size() > cappedSize) {
            merged = merged.subList(0, cappedSize);
        }

        log.info("Merged exceptions inbox: tenantId={} employeeUserId={} checkins={} violations={} total={}",
                tenantId, userId, pendingCheckins.size(), unresolvedViolations.size(), merged.size());

        return ResponseEntity.ok(ApiResponse.success(merged));
    }
}
