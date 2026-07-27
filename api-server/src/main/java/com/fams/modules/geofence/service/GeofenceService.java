package com.fams.modules.geofence.service;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class GeofenceService {

    private final GeofenceRepository geofenceRepository;
    private final SiteRepository siteRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           SiteRepository siteRepository,
                           TenantRepository tenantRepository,
                           UserRoleRepository userRoleRepository,
                           SiteScopeService siteScopeService) {
        this.geofenceRepository = geofenceRepository;
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
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
                .status("active")
                .createdBy(callerUserId)
                .build();

        geofenceRepository.save(geofence);
        log.info("Geofence created: id={} siteId={} tenantId={} by={}", geofence.getId(), siteId, tenantId, callerUserId);
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

        // Supersede the current version and create a new one
        geofenceRepository.supersedeBySiteId(siteId);

        Geofence updated = Geofence.builder()
                .siteId(siteId)
                .tenantId(tenantId)
                .coordinates(newCoordinates)
                .bufferMeters(newBuffer)
                .status("active")
                .createdBy(callerUserId)
                .build();

        geofenceRepository.save(updated);
        log.info("Geofence updated: new id={} siteId={} tenantId={} by={}",
                updated.getId(), siteId, tenantId, callerUserId);
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
        return PageResponse.from(
                geofenceRepository.findBySiteIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId, pageable)
                        .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public Optional<GeofenceResponse> findActiveGeofenceForSite(UUID siteId) {
        return geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active")
                .map(this::toResponse);
    }

    public GeofenceResponse toResponse(Geofence g) {
        return GeofenceResponse.builder()
                .id(g.getId())
                .siteId(g.getSiteId())
                .tenantId(g.getTenantId())
                .coordinates(g.getCoordinates())
                .bufferMeters(g.getBufferMeters())
                .status(g.getStatus())
                .createdBy(g.getCreatedBy())
                .createdAt(g.getCreatedAt())
                .updatedAt(g.getUpdatedAt())
                .build();
    }
}
