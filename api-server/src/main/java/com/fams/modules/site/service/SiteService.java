package com.fams.modules.site.service;

import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.geofence.service.GeofenceService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.shift.service.ShiftService;
import com.fams.modules.site.dto.request.CreateSiteRequest;
import com.fams.modules.site.dto.request.UpdateSiteRequest;
import com.fams.modules.site.dto.response.SiteDetailResponse;
import com.fams.modules.site.dto.response.SiteResponse;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.site.specification.SiteSpecification;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class SiteService {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("name", "code", "status", "timezone", "createdAt", "updatedAt");

    private final SiteRepository siteRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final GeofenceService geofenceService;
    private final ShiftService shiftService;
    private final AssignmentService assignmentService;
    private final PlanLimitEnforcementService planLimitEnforcementService;

    public SiteService(SiteRepository siteRepository,
                       TenantRepository tenantRepository,
                       UserRoleRepository userRoleRepository,
                       SiteScopeService siteScopeService,
                       GeofenceService geofenceService,
                       ShiftService shiftService,
                       AssignmentService assignmentService,
                       PlanLimitEnforcementService planLimitEnforcementService) {
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.geofenceService = geofenceService;
        this.shiftService = shiftService;
        this.assignmentService = assignmentService;
        this.planLimitEnforcementService = planLimitEnforcementService;
    }

    @Transactional
    public SiteResponse createSite(UUID tenantId, CreateSiteRequest request,
                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("sites:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to create sites in this tenant");
            }
        }

        planLimitEnforcementService.assertSiteLimit(tenantId);

        if (siteRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
                tenantId, request.getName().trim())) {
            throw new DuplicateResourceException(
                    "Site '" + request.getName().trim() + "' already exists in this tenant");
        }

        if (StringUtils.hasText(request.getCode())
                && siteRepository.existsByTenantIdAndCodeAndDeletedAtIsNull(
                        tenantId, request.getCode().trim())) {
            throw new DuplicateResourceException(
                    "Site code '" + request.getCode().trim() + "' already exists in this tenant");
        }

        String timezone = StringUtils.hasText(request.getTimezone())
                ? request.getTimezone().trim() : "UTC";
        validateTimezone(timezone);

        String checkinPolicy = StringUtils.hasText(request.getCheckinPolicy())
                ? request.getCheckinPolicy().trim() : "gps_only";
        validateCheckinPolicy(checkinPolicy);

        Site site = Site.builder()
                .tenantId(tenantId)
                .name(request.getName().trim())
                .code(StringUtils.hasText(request.getCode()) ? request.getCode().trim() : null)
                .description(request.getDescription())
                .address(request.getAddress())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .timezone(timezone)
                .status("active")
                .checkinPolicy(checkinPolicy)
                .createdBy(callerUserId)
                .build();

        siteRepository.save(site);
        log.info("Site created: id={} tenantId={} by={}", site.getId(), tenantId, callerUserId);
        return toResponse(site);
    }

    @Transactional(readOnly = true)
    public PageResponse<SiteResponse> listSites(UUID tenantId, String search, String status,
                                                String sortBy, String sortDir, int page, int size,
                                                UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("sites:list")) {
                throw new AccessDeniedException(
                        "You do not have permission to list sites in this tenant");
            }
        }

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "name";
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isPresent() && allowedSiteIds.get().isEmpty()) {
            // Restricted to zero sites (e.g. site-scoped role holder assigned no sites at
            // all) — skip the query entirely, "id IN ()" isn't valid Criteria.
            return PageResponse.from(Page.empty(pageable));
        }

        Specification<Site> spec = SiteSpecification.build(tenantId, search, status, allowedSiteIds.orElse(null));
        Page<SiteResponse> resultPage = siteRepository.findAll(spec, pageable).map(this::toResponse);

        return PageResponse.from(resultPage);
    }

    @Transactional
    public SiteResponse updateSite(UUID tenantId, UUID siteId, UpdateSiteRequest request,
                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("sites:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update sites in this tenant");
            }
        }

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (StringUtils.hasText(request.getName())) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(site.getName())
                    && siteRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
                            tenantId, newName, siteId)) {
                throw new DuplicateResourceException(
                        "Site '" + newName + "' already exists in this tenant");
            }
            site.setName(newName);
        }

        if (request.isClearCode()) {
            site.setCode(null);
        } else if (StringUtils.hasText(request.getCode())) {
            String newCode = request.getCode().trim();
            if (!newCode.equals(site.getCode())
                    && siteRepository.existsByTenantIdAndCodeAndDeletedAtIsNullAndIdNot(
                            tenantId, newCode, siteId)) {
                throw new DuplicateResourceException(
                        "Site code '" + newCode + "' already exists in this tenant");
            }
            site.setCode(newCode);
        }

        if (request.getDescription() != null) {
            site.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        }
        if (request.getAddress() != null) {
            site.setAddress(request.getAddress().isBlank() ? null : request.getAddress());
        }
        if (request.getLatitude() != null)  site.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) site.setLongitude(request.getLongitude());
        if (StringUtils.hasText(request.getTimezone())) {
            String newTimezone = request.getTimezone().trim();
            validateTimezone(newTimezone);
            site.setTimezone(newTimezone);
        }
        if (StringUtils.hasText(request.getStatus()))   site.setStatus(request.getStatus());
        if (StringUtils.hasText(request.getCheckinPolicy())) {
            String newPolicy = request.getCheckinPolicy().trim();
            validateCheckinPolicy(newPolicy);
            site.setCheckinPolicy(newPolicy);
        }

        siteRepository.save(site);
        log.info("Site updated: id={} tenantId={} by={}", siteId, tenantId, callerUserId);
        return toResponse(site);
    }

    /** {@code @Size} alone lets garbage like "Whatever" through — every downstream consumer
     *  (check-in early/late windows, attendance summary, cross-site conflict math) does
     *  {@code ZoneId.of(site.getTimezone())} and would blow up with a raw 500 at check-in time,
     *  long after the typo was saved. Validate it's a real IANA zone ID up front instead. */
    private void validateTimezone(String timezone) {
        try {
            java.time.ZoneId.of(timezone);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "'" + timezone + "' is not a valid IANA timezone name (e.g. 'Asia/Ho_Chi_Minh', 'UTC')");
        }
    }

    private static final Set<String> VALID_CHECKIN_POLICIES = Set.of("gps_only", "gps_face", "gps_face_liveness");

    private void validateCheckinPolicy(String policy) {
        if (!VALID_CHECKIN_POLICIES.contains(policy)) {
            throw new IllegalArgumentException(
                    "checkinPolicy must be one of " + VALID_CHECKIN_POLICIES + ", got '" + policy + "'");
        }
    }

    @Transactional(readOnly = true)
    public SiteDetailResponse getSiteDetail(UUID tenantId, UUID siteId,
                                            UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("sites:read")) {
                throw new AccessDeniedException(
                        "You do not have permission to view sites in this tenant");
            }
        }

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to view this site");
        }

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        return SiteDetailResponse.builder()
                .id(site.getId())
                .tenantId(site.getTenantId())
                .name(site.getName())
                .code(site.getCode())
                .description(site.getDescription())
                .address(site.getAddress())
                .latitude(site.getLatitude())
                .longitude(site.getLongitude())
                .timezone(site.getTimezone())
                .status(site.getStatus())
                .checkinPolicy(site.getCheckinPolicy())
                .createdBy(site.getCreatedBy())
                .geofence(geofenceService.findActiveGeofenceForSite(site.getId()).orElse(null))
                .shifts(shiftService.findActiveShiftsForSite(site.getId()))
                .activeAssignmentCount((int) assignmentService.countActiveAssignmentsForSite(site.getId()))
                .createdAt(site.getCreatedAt())
                .updatedAt(site.getUpdatedAt())
                .build();
    }

    @Transactional
    public void deleteSite(UUID tenantId, UUID siteId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("sites:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to delete sites in this tenant");
            }
        }

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        long activeAssignments = assignmentService.countActiveAssignmentsForSite(siteId);
        if (activeAssignments > 0) {
            throw new IllegalArgumentException(
                    "Site still has " + activeAssignments
                            + " active assignment(s). Reassign or end them before deleting.");
        }

        site.setDeletedAt(java.time.OffsetDateTime.now());
        siteRepository.save(site);
        log.info("Site deleted: id={} tenantId={} by={}", siteId, tenantId, callerUserId);
    }

    public SiteResponse toResponse(Site s) {
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
                .checkinPolicy(s.getCheckinPolicy())
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
