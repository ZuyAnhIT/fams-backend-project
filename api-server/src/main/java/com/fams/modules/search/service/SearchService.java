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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
        Set<String> callerPermissions = Set.of();
        if (!callerIsPlatformAdmin) {
            callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("employees:list")) {
                throw new AccessDeniedException("employees:list permission required for global search");
            }
        }
        boolean canViewSites = callerIsPlatformAdmin
                || callerPermissions.contains("sites:list")
                || callerPermissions.contains("sites:read");
        boolean canListCheckins = callerIsPlatformAdmin || callerPermissions.contains("checkins:list");
        boolean canListViolations = callerIsPlatformAdmin || callerPermissions.contains("violations:list");

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

        String normalizedQuery = q.toLowerCase(Locale.ROOT);
        String pattern = "%" + normalizedQuery + "%";
        boolean employeeIntent = Set.of("nhân viên", "nhan vien", "employee").contains(normalizedQuery);
        boolean siteIntent = Set.of("công trình", "cong trinh", "site").contains(normalizedQuery);
        boolean checkinIntent = Set.of("chấm công", "cham cong", "check-in", "checkin").contains(normalizedQuery);
        boolean violationIntent = Set.of("vi phạm", "vi pham").contains(normalizedQuery);

        // Employee entity carries no direct site column — resolve which employees are visible
        // to a site-restricted caller via their assignments, same linkage the Face ID report uses.
        Optional<Set<UUID>> visibleEmployeeIds = allowedSiteIds.map(sites ->
                assignmentRepository.findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, sites));

        // ── Employee search ────────────────────────────────────────────────────
        Specification<Employee> empSpec = (root, cq, cb) -> {
            var firstLast = cb.lower(cb.concat(
                    cb.concat(root.<String>get("firstName"), " "), root.<String>get("lastName")));
            var lastFirst = cb.lower(cb.concat(
                    cb.concat(root.<String>get("lastName"), " "), root.<String>get("firstName")));
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt"))
            ));
            if (!employeeIntent) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern),
                        cb.like(firstLast, pattern),
                        cb.like(lastFirst, pattern),
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("employeeCode")), pattern),
                        cb.like(cb.lower(root.get("position")), pattern),
                        cb.like(cb.lower(root.get("department")), pattern)
                ));
            }
            visibleEmployeeIds.ifPresent(ids -> predicates.add(root.get("id").in(ids)));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Employee> employees = employeeRepository.findAll(
                empSpec, PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "lastName", "firstName")))
                .getContent();

        // ── Site search ────────────────────────────────────────────────────────
        Specification<Site> siteSpec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt"))
            ));
            if (!siteIntent) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("code")), pattern),
                        cb.like(cb.lower(root.get("address")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                ));
            }
            allowedSiteIds.ifPresent(ids -> predicates.add(root.get("id").in(ids)));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Site> sites = canViewSites
                ? siteRepository.findAll(
                        siteSpec, PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "name")))
                        .getContent()
                : List.of();

        Set<UUID> matchedEmployeeIds = employees.stream().map(Employee::getId).collect(Collectors.toSet());
        Set<UUID> matchedSiteIds = sites.stream().map(Site::getId).collect(Collectors.toSet());

        // ── Check-in search: recent records related to a matched employee/site ─
        // A generic "chấm công" intent intentionally returns the latest visible records.
        List<CheckinRecord> checkins = List.of();
        if (canListCheckins && (checkinIntent || !matchedEmployeeIds.isEmpty() || !matchedSiteIds.isEmpty())) {
            Specification<CheckinRecord> checkinSpec = (root, cq, cb) -> {
                List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.isNull(root.get("deletedAt"))
                ));
                allowedSiteIds.ifPresent(ids -> predicates.add(root.get("siteId").in(ids)));
                if (!checkinIntent) {
                    List<jakarta.persistence.criteria.Predicate> related = new java.util.ArrayList<>();
                    if (!matchedEmployeeIds.isEmpty()) related.add(root.get("employeeId").in(matchedEmployeeIds));
                    if (!matchedSiteIds.isEmpty()) related.add(root.get("siteId").in(matchedSiteIds));
                    predicates.add(cb.or(related.toArray(new jakarta.persistence.criteria.Predicate[0])));
                }
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            };
            PageRequest checkinPage = PageRequest.of(0, limit,
                    Sort.by(Sort.Direction.DESC, "checkInAt"));
            checkins = checkinRepository.findAll(checkinSpec, checkinPage).getContent();
        }

        // ── Violation search: unresolved records related to employee/site ──────
        List<Violation> violations = List.of();
        if (canListViolations && (violationIntent || !matchedEmployeeIds.isEmpty() || !matchedSiteIds.isEmpty())) {
            Specification<Violation> violationSpec = (root, cq, cb) -> {
                List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>(List.of(
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.isNull(root.get("deletedAt")),
                        cb.isFalse(root.get("resolved"))
                ));
                allowedSiteIds.ifPresent(ids -> predicates.add(root.get("siteId").in(ids)));
                if (!violationIntent) {
                    List<jakarta.persistence.criteria.Predicate> related = new java.util.ArrayList<>();
                    if (!matchedEmployeeIds.isEmpty()) related.add(root.get("employeeId").in(matchedEmployeeIds));
                    if (!matchedSiteIds.isEmpty()) related.add(root.get("siteId").in(matchedSiteIds));
                    predicates.add(cb.or(related.toArray(new jakarta.persistence.criteria.Predicate[0])));
                }
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            };
            PageRequest violationPage = PageRequest.of(0, limit,
                    Sort.by(Sort.Direction.DESC, "checkDate"));
            violations = violationRepository.findAll(violationSpec, violationPage).getContent();
        }

        Map<UUID, Employee> employeeContext = new HashMap<>();
        employees.forEach(employee -> employeeContext.put(employee.getId(), employee));
        Set<UUID> contextEmployeeIds = checkins.stream().map(CheckinRecord::getEmployeeId)
                .collect(Collectors.toSet());
        contextEmployeeIds.removeAll(employeeContext.keySet());
        if (!contextEmployeeIds.isEmpty()) {
            employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, contextEmployeeIds)
                    .forEach(employee -> employeeContext.put(employee.getId(), employee));
        }

        Map<UUID, Site> siteContext = new HashMap<>();
        sites.forEach(site -> siteContext.put(site.getId(), site));
        Set<UUID> contextSiteIds = checkins.stream().map(CheckinRecord::getSiteId).collect(Collectors.toSet());
        contextSiteIds.removeAll(siteContext.keySet());
        if (!contextSiteIds.isEmpty()) {
            siteRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, contextSiteIds)
                    .forEach(site -> siteContext.put(site.getId(), site));
        }

        log.info("Global search: tenantId={} q='{}' employees={} sites={} checkins={} violations={}",
                tenantId, q, employees.size(), sites.size(), checkins.size(), violations.size());

        return GlobalSearchResponse.builder()
                .query(q)
                .limit(limit)
                .employees(employees.stream().map(this::toEmployeeResponse).collect(Collectors.toList()))
                .sites(sites.stream().map(this::toSiteResponse).collect(Collectors.toList()))
                .checkins(checkins.stream()
                        .map(checkin -> toCheckinResponse(
                                checkin,
                                employeeContext.get(checkin.getEmployeeId()),
                                siteContext.get(checkin.getSiteId())))
                        .collect(Collectors.toList()))
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

    private CheckinResponse toCheckinResponse(CheckinRecord c, Employee employee, Site site) {
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
                .employeeName(employee == null ? null
                        : (employee.getLastName() + " " + employee.getFirstName()).trim())
                .employeeCode(employee == null ? null : employee.getEmployeeCode())
                .siteName(site == null ? null : site.getName())
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
