package com.fams.modules.geofence.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.geofence.dto.request.CreateGeofenceRequest;
import com.fams.modules.geofence.dto.request.UpdateGeofenceRequest;
import com.fams.modules.geofence.dto.response.GeofenceResponse;
import com.fams.modules.geofence.entity.Geofence;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GeofenceService {

    private final GeofenceRepository geofenceRepository;
    private final SiteRepository siteRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           SiteRepository siteRepository,
                           TenantRepository tenantRepository,
                           UserRoleRepository userRoleRepository,
                           SiteScopeService siteScopeService,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.geofenceRepository = geofenceRepository;
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    /** Approximate polygon area in square metres via the shoelace formula applied to coordinates
     *  projected onto a local equirectangular plane at the polygon's mean latitude. Accurate to
     *  well under 1% for site-scale boundaries (up to a few km²) — full geodesic area calculation
     *  is unnecessary at this scale. */
    private static Double computeAreaSqm(List<List<Double>> coordinates) {
        if (coordinates == null || coordinates.size() < 4) {
            return null;
        }
        double meanLatDeg = coordinates.stream().mapToDouble(c -> c.get(1)).average().orElse(0);
        double meanLatRad = Math.toRadians(meanLatDeg);
        double metersPerDegLat = 111132.92 - 559.82 * Math.cos(2 * meanLatRad) + 1.175 * Math.cos(4 * meanLatRad);
        double metersPerDegLon = 111412.84 * Math.cos(meanLatRad) - 93.5 * Math.cos(3 * meanLatRad);

        double area = 0;
        int n = coordinates.size();
        for (int i = 0; i < n - 1; i++) {
            double x1 = coordinates.get(i).get(0) * metersPerDegLon;
            double y1 = coordinates.get(i).get(1) * metersPerDegLat;
            double x2 = coordinates.get(i + 1).get(0) * metersPerDegLon;
            double y2 = coordinates.get(i + 1).get(1) * metersPerDegLat;
            area += (x1 * y2 - x2 * y1);
        }
        return Math.round(Math.abs(area) / 2.0 * 100) / 100.0;
    }

    private Map<String, Object> geofenceAuditSnapshot(Geofence g) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("siteId", g.getSiteId());
        map.put("bufferMeters", g.getBufferMeters());
        map.put("areaSqm", g.getAreaSqm());
        map.put("pointCount", g.getCoordinates() != null ? g.getCoordinates().size() : 0);
        map.put("status", g.getStatus());
        if (g.getChangeReason() != null) {
            map.put("changeReason", g.getChangeReason());
        }
        return map;
    }

    private void recordAudit(UUID tenantId, UUID actorId, UUID geofenceId, String action,
                              Map<String, Object> before, Map<String, Object> after) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Geofence", geofenceId.toString(), action,
                    before, after,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log action={} geofenceId={}: {}", action, geofenceId, e.getMessage());
        }
    }

    /** Shared GeoJSON-ring validation: enforced here rather than at the DTO layer since it needs
     *  to compare the first and last elements of the list, not just its size. */
    private void assertClosedRing(List<List<Double>> coordinates) {
        List<Double> first = coordinates.get(0);
        List<Double> last = coordinates.get(coordinates.size() - 1);
        if (!first.get(0).equals(last.get(0)) || !first.get(1).equals(last.get(1))) {
            throw new IllegalArgumentException(
                    "Polygon ring must be closed — the last coordinate pair must equal the first");
        }
    }

    @Transactional
    public GeofenceResponse createGeofence(UUID tenantId, UUID siteId,
                                           CreateGeofenceRequest request,
                                           UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("geofences:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to create geofences in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to manage geofences for this site");
        }

        assertClosedRing(request.getCoordinates());

        // Supersede the current active geofence (if any)
        geofenceRepository.supersedeBySiteId(siteId);

        Geofence geofence = Geofence.builder()
                .siteId(siteId)
                .tenantId(tenantId)
                .coordinates(request.getCoordinates())
                .bufferMeters(request.getBufferMeters())
                .areaSqm(computeAreaSqm(request.getCoordinates()))
                .status("active")
                .createdBy(callerUserId)
                .build();

        geofenceRepository.save(geofence);
        log.info("Geofence created: id={} siteId={} tenantId={} by={}", geofence.getId(), siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, geofence.getId(), "geofence_created", null, geofenceAuditSnapshot(geofence));
        return toResponse(geofence);
    }

    @Transactional(readOnly = true)
    public GeofenceResponse getActiveGeofence(UUID tenantId, UUID siteId,
                                              UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("geofences:read")) {
                throw new AccessDeniedException(
                        "You do not have permission to view geofences in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to view geofences for this site");
        }

        return geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active")
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("No active geofence found for site: " + siteId));
    }

    @Transactional
    public GeofenceResponse updateGeofence(UUID tenantId, UUID siteId,
                                           UpdateGeofenceRequest request,
                                           UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("geofences:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update geofences in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to manage geofences for this site");
        }

        Geofence current = geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active geofence found for site: " + siteId));

        if (request.getCoordinates() == null && request.getBufferMeters() == null) {
            throw new IllegalArgumentException("At least one of coordinates or bufferMeters must be provided");
        }

        if (request.getCoordinates() != null) {
            assertClosedRing(request.getCoordinates());
        }

        List<List<Double>> newCoordinates = request.getCoordinates() != null
                ? request.getCoordinates() : current.getCoordinates();
        int newBuffer = request.getBufferMeters() != null
                ? request.getBufferMeters() : current.getBufferMeters();

        Map<String, Object> before = geofenceAuditSnapshot(current);

        // Supersede the current version and create a new one
        geofenceRepository.supersedeBySiteId(siteId);

        Geofence updated = Geofence.builder()
                .siteId(siteId)
                .tenantId(tenantId)
                .coordinates(newCoordinates)
                .bufferMeters(newBuffer)
                .areaSqm(computeAreaSqm(newCoordinates))
                .changeReason(request.getChangeReason())
                .status("active")
                .createdBy(callerUserId)
                .build();

        geofenceRepository.save(updated);
        log.info("Geofence updated: new id={} siteId={} tenantId={} by={}",
                updated.getId(), siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, updated.getId(), "geofence_updated", before, geofenceAuditSnapshot(updated));
        return toResponse(updated);
    }

    @Transactional(readOnly = true)
    public PageResponse<GeofenceResponse> listGeofenceHistory(UUID tenantId, UUID siteId,
                                                              int page, int size,
                                                              UUID callerUserId,
                                                              boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("geofences:read")) {
                throw new AccessDeniedException(
                        "You do not have permission to view geofences in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to view geofences for this site");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Geofence> geofencePage = geofenceRepository.findBySiteIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId, pageable);

        Set<UUID> creatorIds = geofencePage.getContent().stream()
                .map(Geofence::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> nameByCreatorId = userRepository.findAllById(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));

        return PageResponse.from(
                geofencePage.map(g -> toResponse(g, nameByCreatorId.get(g.getCreatedBy()))));
    }

    @Transactional(readOnly = true)
    public Optional<GeofenceResponse> findActiveGeofenceForSite(UUID siteId) {
        return geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active")
                .map(this::toResponse);
    }

    public GeofenceResponse toResponse(Geofence g) {
        String createdByName = g.getCreatedBy() != null
                ? userRepository.findById(g.getCreatedBy()).map(User::getDisplayName).orElse(null)
                : null;
        return toResponse(g, createdByName);
    }

    private GeofenceResponse toResponse(Geofence g, String createdByName) {
        return GeofenceResponse.builder()
                .id(g.getId())
                .siteId(g.getSiteId())
                .tenantId(g.getTenantId())
                .coordinates(g.getCoordinates())
                .bufferMeters(g.getBufferMeters())
                .areaSqm(g.getAreaSqm())
                .changeReason(g.getChangeReason())
                .status(g.getStatus())
                .createdBy(g.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(g.getCreatedAt())
                .updatedAt(g.getUpdatedAt())
                .build();
    }
}
