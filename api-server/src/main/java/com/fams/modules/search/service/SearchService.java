package com.fams.modules.search.service;

import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.dto.response.EmployeeResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.search.dto.response.GlobalSearchResponse;
import com.fams.modules.site.dto.response.SiteResponse;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public SearchService(EmployeeRepository employeeRepository,
                         SiteRepository siteRepository,
                         CheckinRepository checkinRepository,
                         UserRoleRepository userRoleRepository) {
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.userRoleRepository = userRoleRepository;
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

        String q = query == null ? "" : query.trim();

        // Return empty results for blank or single-character queries to avoid full table scans
        if (q.length() < 2) {
            return GlobalSearchResponse.builder()
                    .query(q)
                    .limit(limit)
                    .employees(List.of())
                    .sites(List.of())
                    .checkins(List.of())
                    .build();
        }

        String pattern = "%" + q.toLowerCase() + "%";
        PageRequest topN = PageRequest.of(0, limit);

        // ── Employee search ────────────────────────────────────────────────────
        Specification<Employee> empSpec = (root, cq, cb) -> cb.and(
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
        );
        List<Employee> employees = employeeRepository.findAll(empSpec, topN).getContent();

        // ── Site search ────────────────────────────────────────────────────────
        Specification<Site> siteSpec = (root, cq, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isNull(root.get("deletedAt")),
                cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("code")), pattern),
                        cb.like(cb.lower(root.get("address")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                )
        );
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

        log.info("Global search: tenantId={} q='{}' employees={} sites={} checkins={}",
                tenantId, q, employees.size(), sites.size(), checkins.size());

        return GlobalSearchResponse.builder()
                .query(q)
                .limit(limit)
                .employees(employees.stream().map(this::toEmployeeResponse).collect(Collectors.toList()))
                .sites(sites.stream().map(this::toSiteResponse).collect(Collectors.toList()))
                .checkins(checkins.stream().map(this::toCheckinResponse).collect(Collectors.toList()))
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
}
