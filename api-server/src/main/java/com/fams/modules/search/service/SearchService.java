package com.fams.modules.search.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.dto.response.EmployeeResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.search.dto.response.GlobalSearchResponse;
import com.fams.modules.site.dto.response.SiteResponse;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SearchService {

    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final CheckinRepository checkinRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final AssignmentRepository assignmentRepository;
    private final ViolationRepository violationRepository;

    public SearchService(EmployeeRepository employeeRepository,
                         SiteRepository siteRepository,
                         CheckinRepository checkinRepository,
                         UserRoleRepository userRoleRepository,
                         SiteScopeService siteScopeService,
                         AssignmentRepository assignmentRepository,
                         ViolationRepository violationRepository) {
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.assignmentRepository = assignmentRepository;
        this.violationRepository = violationRepository;
    }

    @Transactional(readOnly = true)
    public GlobalSearchResponse search(UUID tenantId, String query, int limit,
                                       UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:list")) {
                throw new AccessDeniedException("employees:list permission required for global search");
            }
        }

        // Site-scope enforcement (audit 2026-08-04) — global search had no site restriction at
        // all, unlike every other employee-facing list/report in the system (checkin list,
        // Face ID report, violation/attendance reports). A SITE_SUPERVISOR — who legitimately
        // holds employees:list but only for their own site(s) — could otherwise search up and
        // see any employee, site, or check-in tenant-wide, well outside what they're assigned to.
        Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isPresent() && allowedSiteIds.get().isEmpty()) {
            return GlobalSearchResponse.builder()
                    .query(query == null ? "" : query.trim())
                    .limit(limit)
                    .employees(List.of())
                    .sites(List.of())
                    .checkins(List.of())
                    .violations(List.of())
                    .build();
        }

        String q = query == null ? "" : query.trim();

        // Return empty results for blank or single-character queries to avoid full table scans
        if (q.length() < 2) {
            return GlobalSearchResponse.builder()
                    .query(q)
                    .limit(limit)
                    .employees(List.of())
                    .sites(List.of())
                    .checkins(List.of())
                    .violations(List.of())
                    .build();
        }

        String pattern = "%" + q.toLowerCase() + "%";
        PageRequest topN = PageRequest.of(0, limit);

        // Employee entity carries no direct site column — resolve which employees are visible
        // to a site-restricted caller via their assignments, same linkage the Face ID report uses.
        Optional<Set<UUID>> visibleEmployeeIds = allowedSiteIds.map(sites ->
                assignmentRepository.findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, sites));

        // ── Employee search ────────────────────────────────────────────────────
        Specification<Employee> empSpec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt")),
                    cb.or(
                            cb.like(cb.lower(root.get("firstName")), pattern),
                            cb.like(cb.lower(root.get("lastName")), pattern),
                            cb.like(cb.lower(root.get("email")), pattern),
                            cb.like(cb.lower(root.get("employeeCode")), pattern),
                            cb.like(cb.lower(root.get("position")), pattern),
                            cb.like(cb.lower(root.get("department")), pattern)
                    )
            ));
            visibleEmployeeIds.ifPresent(ids -> predicates.add(root.get("id").in(ids)));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Employee> employees = employeeRepository.findAll(empSpec, topN).getContent();

        // ── Site search ────────────────────────────────────────────────────────
        Specification<Site> siteSpec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt")),
                    cb.or(
                            cb.like(cb.lower(root.get("name")), pattern),
                            cb.like(cb.lower(root.get("code")), pattern),
                            cb.like(cb.lower(root.get("address")), pattern),
                            cb.like(cb.lower(root.get("description")), pattern)
                    )
            ));
            allowedSiteIds.ifPresent(ids -> predicates.add(root.get("id").in(ids)));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Site> sites = siteRepository.findAll(siteSpec, topN).getContent();

        // ── Check-in search: recent check-ins for matched employees ────────────
        List<CheckinRecord> checkins = List.of();
        if (!employees.isEmpty()) {
            List<UUID> empIds = employees.stream().map(Employee::getId).collect(Collectors.toList());
            Specification<CheckinRecord> checkinSpec = (root, cq, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt")),
                    root.get("employeeId").in(empIds)
            );
            PageRequest checkinPage = PageRequest.of(0, limit,
                    Sort.by(Sort.Direction.DESC, "checkInAt"));
            checkins = checkinRepository.findAll(checkinSpec, checkinPage).getContent();
        }

        // ── Check-in search: direct lookup by check-in ID (added 2026-08-05) ────
        // Check-ins have no human-readable code (unlike employees/sites), only a UUID — HR
        // pasting/typing a check-in ID (e.g. from a support ticket or an audit-log entry) had no
        // way to find it here before, since the block above only surfaces check-ins belonging to
        // an employee name/code match, not a check-in matched directly. Exact-UUID only (no
        // prefix match — CheckinRecord.id is a real uuid column, not text, so a LIKE-prefix scan
        // would require a full-table cast; a support ticket ID is always copy-pasted whole).
        try {
            UUID checkinId = UUID.fromString(q);
            java.util.Optional<CheckinRecord> byId = checkinRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                    .filter(c -> allowedSiteIds.isEmpty() || allowedSiteIds.get().contains(c.getSiteId()));
            if (byId.isPresent() && checkins.stream().noneMatch(c -> c.getId().equals(checkinId))) {
                List<CheckinRecord> merged = new java.util.ArrayList<>();
                merged.add(byId.get());
                merged.addAll(checkins);
                checkins = merged.size() > limit ? merged.subList(0, limit) : merged;
            }
        } catch (IllegalArgumentException notAUuid) {
            // q isn't a UUID — nothing to look up directly, employee-linked check-ins above stand.
        }

        // ── Violation search (#128, 2026-08-18) — the AC calls for grouping results into
        // employee/site/checkin/violation, but this group never existed even though the
        // violation module has existed since #114-118. Same pattern as check-ins: unresolved
        // violations for employees that matched the query, plus a direct exact-UUID lookup.
        List<Violation> violations = List.of();
        if (!employees.isEmpty()) {
            List<UUID> empIds = employees.stream().map(Employee::getId).collect(Collectors.toList());
            Specification<Violation> violationSpec = (root, cq, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt")),
                    cb.isFalse(root.get("resolved")),
                    root.get("employeeId").in(empIds)
            );
            PageRequest violationPage = PageRequest.of(0, limit,
                    Sort.by(Sort.Direction.DESC, "checkDate"));
            violations = violationRepository.findAll(violationSpec, violationPage).getContent();
        }

        try {
            UUID violationId = UUID.fromString(q);
            java.util.Optional<Violation> byId = violationRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                    .filter(v -> allowedSiteIds.isEmpty() || allowedSiteIds.get().contains(v.getSiteId()));
            if (byId.isPresent() && violations.stream().noneMatch(v -> v.getId().equals(violationId))) {
                List<Violation> merged = new java.util.ArrayList<>();
                merged.add(byId.get());
                merged.addAll(violations);
                violations = merged.size() > limit ? merged.subList(0, limit) : merged;
            }
        } catch (IllegalArgumentException notAUuid) {
            // q isn't a UUID — nothing to look up directly, employee-linked violations above stand.
        }

        log.info("Global search: tenantId={} q='{}' employees={} sites={} checkins={} violations={}",
                tenantId, q, employees.size(), sites.size(), checkins.size(), violations.size());

        return GlobalSearchResponse.builder()
                .query(q)
                .limit(limit)
                .employees(employees.stream().map(this::toEmployeeResponse).collect(Collectors.toList()))
                .sites(sites.stream().map(this::toSiteResponse).collect(Collectors.toList()))
                .checkins(checkins.stream().map(this::toCheckinResponse).collect(Collectors.toList()))
                .violations(violations.stream().map(this::toViolationResponse).collect(Collectors.toList()))
                .build();
    }

    private EmployeeResponse toEmployeeResponse(Employee e) {
        return EmployeeResponse.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .userId(e.getUserId())
                .employeeCode(e.getEmployeeCode())
                .firstName(e.getFirstName())
                .lastName(e.getLastName())
                .email(e.getEmail())
                .phone(e.getPhone())
                .position(e.getPosition())
                .department(e.getDepartment())
                .status(e.getStatus())
                .hiredDate(e.getHiredDate())
                .avatarUrl(e.getAvatarUrl())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private SiteResponse toSiteResponse(Site s) {
        return SiteResponse.builder()
                .id(s.getId())
                .tenantId(s.getTenantId())
                .name(s.getName())
                .code(s.getCode())
                .description(s.getDescription())
                .address(s.getAddress())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .timezone(s.getTimezone())
                .status(s.getStatus())
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private CheckinResponse toCheckinResponse(CheckinRecord c) {
        return CheckinResponse.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .siteId(c.getSiteId())
                .employeeId(c.getEmployeeId())
                .assignmentId(c.getAssignmentId())
                .shiftId(c.getShiftId())
                .status(c.getStatus())
                .checkInAt(c.getCheckInAt())
                .checkInLat(c.getCheckInLat())
                .checkInLon(c.getCheckInLon())
                .checkInAccuracy(c.getCheckInAccuracy())
                .checkInInsideGeofence(c.isCheckInInsideGeofence())
                .checkOutAt(c.getCheckOutAt())
                .workMinutes(c.getWorkMinutes())
                .gpsRiskScore(c.getGpsRiskScore())
                .deviceId(c.getDeviceId())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private ViolationListResponse toViolationResponse(Violation v) {
        return ViolationListResponse.builder()
                .id(v.getId())
                .employeeId(v.getEmployeeId())
                .siteId(v.getSiteId())
                .violationType(v.getViolationType())
                .checkDate(v.getCheckDate())
                .description(v.getDescription())
                .resolved(v.isResolved())
                .resolvedAt(v.getResolvedAt())
                .resolution(v.getResolution())
                .affectsAttendance(v.isAffectsAttendance())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
