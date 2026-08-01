package com.fams.modules.randomcheck.controller;

import com.fams.modules.randomcheck.dto.request.GenerateScheduledChecksRequest;
import com.fams.modules.randomcheck.dto.request.ManualCheckRequest;
import com.fams.modules.randomcheck.dto.request.SubmitCheckResponseRequest;
import com.fams.modules.randomcheck.dto.response.CheckResponseDto;
import com.fams.modules.randomcheck.dto.response.EmployeePendingCheckResponse;
import com.fams.modules.randomcheck.dto.response.ScheduledCheckDetailResponse;
import com.fams.modules.randomcheck.dto.response.ScheduledCheckResponse;
import com.fams.modules.randomcheck.entity.CheckResponse;
import com.fams.modules.randomcheck.repository.CheckResponseRepository;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.randomcheck.service.CheckResponseService;
import com.fams.modules.randomcheck.service.ManualCheckService;
import com.fams.modules.randomcheck.service.NoResponseViolationService;
import com.fams.modules.randomcheck.service.RandomCheckDispatchService;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.randomcheck.service.ScheduledCheckGeneratorService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.ai.AiServiceClient;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "Scheduled Checks", description = "Random check schedule generation and listing")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/scheduled-checks")
public class ScheduledCheckController {

    private static final String PERM_CONFIGURE = "randomchecks:configure";

    // How far in advance a 'pending' (not-yet-dispatched) check becomes visible to the employee
    // via GET /my-pending — deliberately tight, matched to the dispatch job's own poll interval,
    // so employees can never read the day's random-check schedule off the API in advance. See
    // ScheduledCheckRepository.findPendingForEmployeeDueSoon.
    @org.springframework.beans.factory.annotation.Value(
            "${fams.randomcheck.my-pending.pending-lookahead-seconds:60}")
    private long pendingLookaheadSeconds;

    private final ScheduledCheckGeneratorService generatorService;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final CheckResponseRepository checkResponseRepository;
    private final UserRoleRepository userRoleRepository;
    private final RandomCheckDispatchService dispatchService;
    private final RandomCheckDispatchQueue dispatchQueue;
    private final ScheduledCheckCancelService cancelService;
    private final CheckResponseService checkResponseService;
    private final EmployeeRepository employeeRepository;
    private final NoResponseViolationService noResponseViolationService;
    private final ManualCheckService manualCheckService;
    private final SiteScopeService siteScopeService;
    private final SiteRepository siteRepository;
    private final AiServiceClient aiServiceClient;

    public ScheduledCheckController(ScheduledCheckGeneratorService generatorService,
                                    ScheduledCheckRepository scheduledCheckRepository,
                                    CheckResponseRepository checkResponseRepository,
                                    UserRoleRepository userRoleRepository,
                                    RandomCheckDispatchService dispatchService,
                                    RandomCheckDispatchQueue dispatchQueue,
                                    ScheduledCheckCancelService cancelService,
                                    CheckResponseService checkResponseService,
                                    EmployeeRepository employeeRepository,
                                    NoResponseViolationService noResponseViolationService,
                                    ManualCheckService manualCheckService,
                                    SiteScopeService siteScopeService,
                                    SiteRepository siteRepository,
                                    AiServiceClient aiServiceClient) {
        this.generatorService = generatorService;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.checkResponseRepository = checkResponseRepository;
        this.userRoleRepository = userRoleRepository;
        this.dispatchService = dispatchService;
        this.dispatchQueue = dispatchQueue;
        this.cancelService = cancelService;
        this.checkResponseService = checkResponseService;
        this.employeeRepository = employeeRepository;
        this.noResponseViolationService = noResponseViolationService;
        this.manualCheckService = manualCheckService;
        this.siteScopeService = siteScopeService;
        this.siteRepository = siteRepository;
        this.aiServiceClient = aiServiceClient;
    }

    @Operation(
        summary = "Trigger scheduled check generation for a date",
        description = "Manually triggers the generation of random check schedules for all active assignments " +
                      "in this tenant on the specified date (defaults to today). " +
                      "The operation is idempotent — running it again for the same date skips already-generated assignments. " +
                      "Normally handled automatically by the daily cron job (00:01 AM). " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Generation complete — returns count of new scheduled checks created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @RequestBody(required = false) GenerateScheduledChecksRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());

        LocalDate date = (request != null && request.getDate() != null)
                ? request.getDate() : LocalDate.now();

        log.info("Manual generation trigger tenantId={} date={} userId={}", tenantId, date, userDetails.getUserId());
        int created = generatorService.generateForTenantAndDate(tenantId, date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("created", created);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "List scheduled checks with filters and pagination",
        description = "Returns a paginated list of scheduled random checks for this tenant. " +
                      "All filter parameters are optional. Supports filtering by site, employee, status, " +
                      "and date range. Results ordered by checkDate DESC, scheduledAt DESC. " +
                      "Requires randomchecks:list or randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated list of scheduled checks returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:list permission")
    })
    @PreAuthorize("hasAnyAuthority('randomchecks:list', 'randomchecks:configure')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ScheduledCheckResponse>>> list(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Filter by site UUID") @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by employee UUID") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by status (pending/sent/responded/no_response/cancelled)")
                @RequestParam(required = false) String status,
            @Parameter(description = "Filter from date (inclusive)", example = "2026-06-01")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Filter to date (inclusive)", example = "2026-06-30")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "Page number (0-based, default 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20)") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        if (!userDetails.isPlatformAdmin()) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                    userDetails.getUserId(), tenantId);
            if (!perms.contains("randomchecks:list") && !perms.contains(PERM_CONFIGURE)) {
                throw new AccessDeniedException("Missing permission: randomchecks:list");
            }
        }

        java.util.Optional<UUID> effectiveSiteFilter;
        try {
            effectiveSiteFilter = resolveSiteFilter(userDetails, tenantId, siteId);
        } catch (NoSitesAllowed e) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.from(Page.empty(PageRequest.of(page, size)))));
        }
        siteId = effectiveSiteFilter.orElse(null);

        PageRequest pageable = PageRequest.of(page, size);
        LocalDate from = dateFrom != null ? dateFrom : LocalDate.of(1970, 1, 1);
        LocalDate to = dateTo != null ? dateTo : LocalDate.of(2099, 12, 31);
        Page<ScheduledCheck> rawPage = scheduledCheckRepository
                .findByTenantWithFilters(tenantId, siteId, employeeId, status, from, to, pageable);

        // Batch-hydrate employee/site names and outcome/failureReason for the page — found via FE
        // audit (2026-07-31): the list previously returned bare IDs with no result, forcing the
        // Web client to resolve names via a separate directory call and outcome via a per-row
        // detail call (N+1). One extra query per lookup type for the whole page, not per row.
        List<UUID> checkIds = rawPage.getContent().stream().map(ScheduledCheck::getId).collect(Collectors.toList());
        Set<UUID> empIds = rawPage.getContent().stream().map(ScheduledCheck::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> siteIds = rawPage.getContent().stream().map(ScheduledCheck::getSiteId).collect(Collectors.toSet());

        Map<UUID, String> empNames = empIds.isEmpty() ? Map.of()
                : employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, empIds).stream()
                        .collect(Collectors.toMap(e -> e.getId(), e -> (e.getFirstName() + " " + e.getLastName()).trim()));
        Map<UUID, String> siteNames = siteIds.isEmpty() ? Map.of()
                : siteRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, siteIds).stream()
                        .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
        Map<UUID, CheckResponse> responsesByCheckId = checkIds.isEmpty() ? Map.of()
                : checkResponseRepository.findAllByScheduledCheckIdIn(checkIds).stream()
                        .collect(Collectors.toMap(CheckResponse::getScheduledCheckId, r -> r));

        Page<ScheduledCheckResponse> resultPage = rawPage.map(s -> {
            CheckResponse resp = responsesByCheckId.get(s.getId());
            return toResponse(s, empNames.get(s.getEmployeeId()), siteNames.get(s.getSiteId()),
                    resp != null ? resp.getOutcome() : null, resp != null ? resp.getFailureReason() : null);
        });

        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(resultPage)));
    }

    @Operation(
        summary = "Employee: list my pending / sent checks with countdown",
        description = "Returns all random checks assigned to the authenticated employee in this tenant " +
                      "that are in 'pending' or 'sent' status (or a specific status supplied via the ?status param). " +
                      "Each item includes a `secondsRemaining` field calculated as (expiresAt - now) in seconds " +
                      "— negative values mean the window has already closed. " +
                      "Any authenticated tenant member can call this endpoint; only their own checks are returned."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "List of pending/sent checks for the authenticated employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No employee record found for this user in this tenant")
    })
    @GetMapping("/my-pending")
    public ResponseEntity<ApiResponse<List<EmployeePendingCheckResponse>>> myPending(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Status filter — defaults to 'pending,sent' when omitted. " +
                                     "Pass 'pending' or 'sent' to restrict to one status.")
                @RequestParam(required = false) String status,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        UUID employeeId = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(userDetails.getUserId(), tenantId)
                .map(e -> e.getId())
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Employee record not found for this user in tenant " + tenantId));

        OffsetDateTime now = OffsetDateTime.now();
        // A 'pending' check must never be visible to the employee meaningfully earlier than
        // dispatch would have surfaced it anyway — bounded to the dispatch job's own poll
        // interval (default 60s) so "pending, due soon" carries no more advance notice than the
        // ~60s latency already inherent in how dispatch works. See findPendingForEmployeeDueSoon.
        OffsetDateTime pendingCutoff = now.plusSeconds(pendingLookaheadSeconds);

        List<ScheduledCheck> checks;
        if (status != null && !status.isBlank()) {
            if ("pending".equals(status)) {
                // Same time-bound as the default view — an explicit ?status=pending request
                // must not be a backdoor around the cutoff below.
                checks = scheduledCheckRepository
                        .findPendingForEmployeeDueSoon(tenantId, employeeId, pendingCutoff);
            } else {
                // Any other explicit status (sent, responded, no_response, cancelled) carries no
                // "reveal the future schedule" risk — already happened or already dispatched.
                checks = scheduledCheckRepository
                        .findByTenantWithFilters(tenantId, null, employeeId, status,
                                LocalDate.of(1970, 1, 1), LocalDate.of(2099, 12, 31),
                                PageRequest.of(0, 1000, Sort.by(Sort.Direction.ASC, "expiresAt")))
                        .getContent();
            }
        } else {
            // Default: pending-due-soon + sent
            checks = scheduledCheckRepository
                    .findPendingForEmployeeDueSoon(tenantId, employeeId, pendingCutoff);
            List<ScheduledCheck> sent = scheduledCheckRepository
                    .findByTenantWithFilters(tenantId, null, employeeId, "sent",
                            LocalDate.of(1970, 1, 1), LocalDate.of(2099, 12, 31),
                            PageRequest.of(0, 1000, Sort.by(Sort.Direction.ASC, "expiresAt")))
                    .getContent();
            checks = new java.util.ArrayList<>(checks);
            checks.addAll(sent);
            checks.sort(java.util.Comparator.comparing(ScheduledCheck::getExpiresAt));
        }

        List<EmployeePendingCheckResponse> result = checks.stream()
                .map(s -> toPendingResponse(s, now))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get scheduled check status summary",
        description = "Returns a count breakdown of scheduled checks by status for this tenant. " +
                      "Supports optional filtering by date range and site. " +
                      "Requires randomchecks:list or randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Status summary returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:list permission")
    })
    @PreAuthorize("hasAnyAuthority('randomchecks:list', 'randomchecks:configure')")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Filter from date (inclusive)", example = "2026-06-01")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Filter to date (inclusive)", example = "2026-06-30")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "Filter by site UUID") @RequestParam(required = false) UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        if (!userDetails.isPlatformAdmin()) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                    userDetails.getUserId(), tenantId);
            if (!perms.contains("randomchecks:list") && !perms.contains(PERM_CONFIGURE)) {
                throw new AccessDeniedException("Missing permission: randomchecks:list");
            }
        }

        try {
            siteId = resolveSiteFilter(userDetails, tenantId, siteId).orElse(null);
        } catch (NoSitesAllowed e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("counts", Map.of("total", 0L));
            return ResponseEntity.ok(ApiResponse.success(empty));
        }

        LocalDate from = dateFrom != null ? dateFrom : LocalDate.of(1970, 1, 1);
        LocalDate to = dateTo != null ? dateTo : LocalDate.of(2099, 12, 31);
        List<Object[]> rows = scheduledCheckRepository.countByStatusGrouped(tenantId, from, to, siteId);
        Map<String, Object> counts = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : rows) {
            String s = (String) row[0];
            long c = ((Number) row[1]).longValue();
            counts.put(s, c);
            total += c;
        }
        counts.put("total", total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        if (dateFrom != null) result.put("dateFrom", dateFrom.toString());
        if (dateTo != null) result.put("dateTo", dateTo.toString());
        if (siteId != null) result.put("siteId", siteId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get scheduled check detail",
        description = "Returns the full detail of a single scheduled check, including the employee's response " +
                      "if the status is 'responded'. Used by HR to review check outcomes and handle disputes. " +
                      "Requires randomchecks:list or randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check detail returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Scheduled check not found or does not belong to this tenant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:list permission")
    })
    @PreAuthorize("hasAnyAuthority('randomchecks:list', 'randomchecks:configure')")
    @GetMapping("/{checkId}")
    public ResponseEntity<ApiResponse<ScheduledCheckDetailResponse>> getDetail(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        if (!userDetails.isPlatformAdmin()) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                    userDetails.getUserId(), tenantId);
            if (!perms.contains("randomchecks:list") && !perms.contains(PERM_CONFIGURE)) {
                throw new AccessDeniedException("Missing permission: randomchecks:list");
            }
        }

        ScheduledCheck check = scheduledCheckRepository
                .findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Scheduled check not found: " + checkId));
        assertCheckInScope(userDetails, tenantId, check);

        CheckResponseDto responseDto = null;
        if ("responded".equals(check.getStatus())) {
            responseDto = checkResponseRepository.findByScheduledCheckId(checkId)
                    .map(this::toCheckResponseDto)
                    .orElse(null);
        }

        return ResponseEntity.ok(ApiResponse.success(toDetailResponse(check, responseDto)));
    }

    @Operation(
        summary = "HR: view the selfie submitted for a random check response",
        description = "Streams the JPEG selfie fams-ai persisted for this check's response (face/liveness "
                      + "modes only). Returns 404 if the check hasn't been responded to, no photo was ever "
                      + "submitted (see CheckResponseDto.hasPhotoEvidence — check that first to avoid an "
                      + "unnecessary call), or fams-ai no longer has the file. Same permission/site-scope as "
                      + "GET /{checkId}. Found missing entirely via FE audit (2026-08-01) — fams-ai already "
                      + "saves these photos, but nothing exposed a way to retrieve one back."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "JPEG image bytes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check not found, not yet responded, no photo was submitted, or fams-ai has no file")
    })
    @PreAuthorize("hasAnyAuthority('randomchecks:list', 'randomchecks:configure')")
    @GetMapping("/{checkId}/photo")
    public ResponseEntity<byte[]> getResponsePhoto(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        if (!userDetails.isPlatformAdmin()) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                    userDetails.getUserId(), tenantId);
            if (!perms.contains("randomchecks:list") && !perms.contains(PERM_CONFIGURE)) {
                throw new AccessDeniedException("Missing permission: randomchecks:list");
            }
        }

        ScheduledCheck check = scheduledCheckRepository
                .findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Scheduled check not found: " + checkId));
        assertCheckInScope(userDetails, tenantId, check);

        CheckResponse response = checkResponseRepository.findByScheduledCheckId(checkId)
                .filter(CheckResponse::isPhotoSubmitted)
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "No photo evidence for check: " + checkId));

        byte[] photo = aiServiceClient.getCheckinPhoto(tenantId, response.getId());
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_JPEG).body(photo);
    }

    @Operation(
        summary = "Submit a response to a scheduled check",
        description = "Called by the employee to respond to a random check. " +
                      "Requires location (lat/lon). Face image and liveness score are optional, " +
                      "required depending on the check mode. " +
                      "Returns HTTP 410 Gone if the response window has already expired (now > expires_at). " +
                      "Returns HTTP 400 if the check is not in a respondable state."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Response recorded successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Check not in respondable state (pending/cancelled/already responded)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Scheduled check not found or does not belong to this employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "410",
            description = "Response window has expired — expires_at is in the past")
    })
    @PostMapping("/{checkId}/respond")
    public ResponseEntity<ApiResponse<CheckResponseDto>> respond(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @Valid @RequestBody SubmitCheckResponseRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        UUID employeeId = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(userDetails.getUserId(), tenantId)
                .map(e -> e.getId())
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Employee record not found for this tenant"));

        CheckResponseDto dto = checkResponseService.submit(tenantId, checkId, employeeId, request);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @Operation(
        summary = "Employee: poll my own check's result",
        description = "Employee-owned, safe view of a scheduled check's result — for polling the outcome "
                      + "after respond() when the mode requires async face/liveness verification (faceVerified "
                      + "stays null in the immediate respond() response until the AI callback arrives). "
                      + "Unlike GET /{checkId} (HR-only, requires randomchecks:list/:configure), this endpoint "
                      + "requires no special permission — any authenticated tenant member may call it, but only "
                      + "for a check that belongs to THEIR OWN employee record; any other check (including one "
                      + "belonging to a different employee) returns 404, never 403, to avoid confirming the "
                      + "check's existence. Never returns raw embeddings, image storage paths, or another "
                      + "employee's data. Found missing via FE audit (2026-07-31) — the employee-facing app had "
                      + "no way at all to learn the final async result of its own submission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Result returned (may still be processingStatus=pending)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check not found, or does not belong to the calling employee")
    })
    @GetMapping("/{checkId}/my-result")
    public ResponseEntity<ApiResponse<com.fams.modules.randomcheck.dto.response.MyCheckResultResponse>> myResult(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        UUID employeeId = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(userDetails.getUserId(), tenantId)
                .map(e -> e.getId())
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Employee record not found for this tenant"));

        ScheduledCheck check = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .filter(c -> c.getEmployeeId().equals(employeeId))
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Scheduled check not found: " + checkId));

        CheckResponse resp = checkResponseRepository.findByScheduledCheckId(checkId).orElse(null);

        boolean faceRequired = extractCheckMode(check.getConfigSnapshot()).startsWith("location_face");
        boolean stillProcessing = resp != null && faceRequired && resp.getFaceVerified() == null;
        String processingStatus = stillProcessing ? "pending" : "completed";

        return ResponseEntity.ok(ApiResponse.success(
                com.fams.modules.randomcheck.dto.response.MyCheckResultResponse.builder()
                        .checkId(check.getId())
                        .status(check.getStatus())
                        .processingStatus(processingStatus)
                        .outcome(resp != null ? resp.getOutcome() : null)
                        .failureReason(resp != null ? resp.getFailureReason() : null)
                        .locationVerified(resp != null ? resp.isLocationVerified() : null)
                        .faceVerified(resp != null ? resp.getFaceVerified() : null)
                        .livenessVerified(resp != null ? resp.getLivenessVerified() : null)
                        .faceVerifyScore(resp != null ? resp.getFaceVerifyScore() : null)
                        .respondedAt(resp != null ? resp.getRespondedAt() : null)
                        .build()));
    }

    @Operation(
        summary = "Cancel a scheduled check",
        description = "Cancels a single scheduled check, removing it from the Redis dispatch queue. " +
                      "Only checks with status 'pending' or 'sent' can be cancelled. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check cancelled successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Check is already in a terminal state (responded/no_response/cancelled)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Scheduled check not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/{checkId}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelCheck(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());
        ScheduledCheck checkToCancel = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Scheduled check not found: " + checkId));
        assertCheckInScope(userDetails, tenantId, checkToCancel);
        cancelService.cancelCheck(tenantId, checkId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkId", checkId);
        result.put("cancelled", true);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Manually dispatch a scheduled check",
        description = "Forces immediate dispatch of a single scheduled check by ID, " +
                      "transitioning its status from 'pending' to 'sent'. " +
                      "Useful for testing and manual intervention. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check dispatched (or already past pending — no-op)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/{checkId}/dispatch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dispatchCheck(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Scheduled check UUID") @PathVariable UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());
        ScheduledCheck checkToDispatch = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new com.fams.shared.exception.ResourceNotFoundException(
                        "Scheduled check not found: " + checkId));
        assertCheckInScope(userDetails, tenantId, checkToDispatch);
        dispatchService.dispatch(checkId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkId", checkId);
        result.put("dispatched", true);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get dispatch queue status",
        description = "Returns the current size of the Redis dispatch queue and, optionally, " +
                      "the scheduled score (epoch-seconds) of a specific check. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Queue status returned")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @GetMapping("/dispatch-queue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queueStatus(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Optional check ID to look up in queue")
                @RequestParam(required = false) UUID checkId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queueSize", dispatchQueue.queueSize());
        if (checkId != null) {
            Double score = dispatchQueue.scheduledScore(checkId);
            result.put("checkId", checkId);
            result.put("inQueue", score != null);
            result.put("scheduledEpochSeconds", score);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Trigger an immediate manual random check for an employee",
        description = "HR/Supervisor sends an on-demand random check directly to an employee at a site. " +
                      "The check is created with status 'sent' and expires after the configured " +
                      "responseWindowSeconds. The employee must have an active assignment at the site today. " +
                      "Bypasses the config's applicableRoles population filter by design (targeting one " +
                      "specific employee is an explicit override) — a reason is required for audit trail. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Manual check created and sent to the employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Employee has no active assignment at site, or no config exists, or invalid checkMode"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Site not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/manual")
    public ResponseEntity<ApiResponse<ScheduledCheckResponse>> triggerManualCheck(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody ManualCheckRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());
        com.fams.modules.randomcheck.entity.ScheduledCheck check =
                manualCheckService.trigger(tenantId, request, userDetails.getUserId());
        return ResponseEntity.status(201).body(ApiResponse.success(toResponse(check)));
    }

    @Operation(
        summary = "Process expired checks and create no_response violations",
        description = "Manually triggers the no-response violation processor for this tenant. " +
                      "Marks all 'sent' checks whose expires_at has passed as 'no_response' " +
                      "and creates a violation record for each. Normally runs automatically every 2 minutes. " +
                      "Requires randomchecks:configure permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Processing complete — returns count of violations created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing randomchecks:configure permission")
    })
    @PreAuthorize("hasAuthority('randomchecks:configure')")
    @PostMapping("/process-expired")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processExpired(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        checkPermission(userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());
        int created = noResponseViolationService.processExpiredForTenant(tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("violationsCreated", created);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private EmployeePendingCheckResponse toPendingResponse(ScheduledCheck s, OffsetDateTime now) {
        Long secondsRemaining = null;
        if (s.getExpiresAt() != null && "sent".equals(s.getStatus())) {
            secondsRemaining = ChronoUnit.SECONDS.between(now, s.getExpiresAt());
        }
        return EmployeePendingCheckResponse.builder()
                .id(s.getId())
                .tenantId(s.getTenantId())
                .assignmentId(s.getAssignmentId())
                .employeeId(s.getEmployeeId())
                .siteId(s.getSiteId())
                .shiftId(s.getShiftId())
                .configId(s.getConfigId())
                .configSnapshot(s.getConfigSnapshot())
                .checkDate(s.getCheckDate())
                .checkIndex(s.getCheckIndex())
                .scheduledAt(s.getScheduledAt())
                .expiresAt(s.getExpiresAt())
                .status(s.getStatus())
                .secondsRemaining(secondsRemaining)
                .createdAt(s.getCreatedAt())
                .build();
    }

    private void checkPermission(UUID callerId, UUID tenantId, boolean isPlatformAdmin) {
        if (isPlatformAdmin) return;
        Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerId, tenantId);
        if (!perms.contains(PERM_CONFIGURE)) {
            throw new AccessDeniedException("Missing permission: " + PERM_CONFIGURE);
        }
    }

    /** Thrown internally by resolveSiteFilter to signal "caller is restricted to zero sites" —
     *  callers catch this and return an empty result instead of querying. */
    private static final class NoSitesAllowed extends RuntimeException {
    }

    /** Resolves the caller's site scope against an explicitly requested siteId filter (if
     *  any), mirroring the same pattern used in CheckinService/AttendanceSummaryService.
     *  Returns the siteId to actually query with (empty Optional = caller unrestricted, no
     *  specific-site filter to apply). */
    private java.util.Optional<UUID> resolveSiteFilter(FamsUserDetails userDetails, UUID tenantId, UUID requestedSiteId) {
        java.util.Optional<Set<UUID>> allowed = siteScopeService.resolveAllowedSiteIds(
                userDetails.getUserId(), tenantId, userDetails.isPlatformAdmin());
        if (allowed.isEmpty()) {
            return java.util.Optional.ofNullable(requestedSiteId);
        }
        Set<UUID> allowedSites = allowed.get();
        if (allowedSites.isEmpty()) {
            throw new NoSitesAllowed();
        }
        if (requestedSiteId != null) {
            if (!allowedSites.contains(requestedSiteId)) {
                throw new AccessDeniedException("You do not have permission to view random checks for this site");
            }
            return java.util.Optional.of(requestedSiteId);
        }
        if (allowedSites.size() == 1) {
            return java.util.Optional.of(allowedSites.iterator().next());
        }
        throw new AccessDeniedException(
                "You are scoped to multiple sites — pass a specific siteId to list its random checks");
    }

    /** Single-check-scoped variant for getDetail/cancelCheck/dispatchCheck, which act on a
     *  specific check already loaded from the DB rather than a list filter. */
    private void assertCheckInScope(FamsUserDetails userDetails, UUID tenantId, ScheduledCheck check) {
        if (!siteScopeService.isSiteAllowed(userDetails.getUserId(), tenantId, check.getSiteId(), userDetails.isPlatformAdmin())) {
            throw new AccessDeniedException("You do not have permission to act on this check's site");
        }
    }

    /** Mirrors CheckResponseService's private helper of the same purpose — reads checkMode back out
     *  of the JSON snapshot captured at generation time. Small duplication across the module's
     *  services/controller (each already parses this snapshot independently) rather than a shared
     *  utility for one two-line regex. */
    private String extractCheckMode(String configSnapshot) {
        if (configSnapshot == null) return "location_only";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"checkMode\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(configSnapshot);
        return m.find() ? m.group(1) : "location_only";
    }

    private ScheduledCheckDetailResponse toDetailResponse(ScheduledCheck s, CheckResponseDto responseDto) {
        return ScheduledCheckDetailResponse.builder()
                .id(s.getId())
                .tenantId(s.getTenantId())
                .assignmentId(s.getAssignmentId())
                .employeeId(s.getEmployeeId())
                .siteId(s.getSiteId())
                .shiftId(s.getShiftId())
                .configId(s.getConfigId())
                .configSnapshot(s.getConfigSnapshot())
                .checkDate(s.getCheckDate())
                .checkIndex(s.getCheckIndex())
                .scheduledAt(s.getScheduledAt())
                .expiresAt(s.getExpiresAt())
                .status(s.getStatus())
                .createdAt(s.getCreatedAt())
                .response(responseDto)
                .manualReason(s.getManualReason())
                .triggeredBy(s.getTriggeredBy())
                .build();
    }

    private CheckResponseDto toCheckResponseDto(CheckResponse r) {
        return CheckResponseDto.builder()
                .id(r.getId())
                .scheduledCheckId(r.getScheduledCheckId())
                .employeeId(r.getEmployeeId())
                .respondedAt(r.getRespondedAt())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .accuracyMeters(r.getAccuracyMeters())
                .locationVerified(r.isLocationVerified())
                .faceVerified(r.getFaceVerified())
                .livenessVerified(r.getLivenessVerified())
                .outcome(r.getOutcome())
                .failureReason(r.getFailureReason())
                .faceVerifyScore(r.getFaceVerifyScore())
                .hasPhotoEvidence(r.isPhotoSubmitted())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private ScheduledCheckResponse toResponse(ScheduledCheck s) {
        return toResponse(s, null, null, null, null);
    }

    private ScheduledCheckResponse toResponse(ScheduledCheck s, String employeeName, String siteName,
                                              String outcome, String failureReason) {
        return ScheduledCheckResponse.builder()
                .id(s.getId())
                .tenantId(s.getTenantId())
                .assignmentId(s.getAssignmentId())
                .employeeId(s.getEmployeeId())
                .employeeName(employeeName)
                .siteId(s.getSiteId())
                .siteName(siteName)
                .shiftId(s.getShiftId())
                .configId(s.getConfigId())
                .configSnapshot(s.getConfigSnapshot())
                .checkDate(s.getCheckDate())
                .checkIndex(s.getCheckIndex())
                .scheduledAt(s.getScheduledAt())
                .expiresAt(s.getExpiresAt())
                .status(s.getStatus())
                .createdAt(s.getCreatedAt())
                .manualReason(s.getManualReason())
                .triggeredBy(s.getTriggeredBy())
                .outcome(outcome)
                .failureReason(failureReason)
                .build();
    }
}
