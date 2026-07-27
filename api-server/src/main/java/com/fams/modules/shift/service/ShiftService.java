package com.fams.modules.shift.service;

import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.shift.dto.request.ConfigureShiftOtRequest;
import com.fams.modules.shift.dto.request.CreateShiftRequest;
import com.fams.modules.shift.dto.request.UpdateShiftRequest;
import com.fams.modules.shift.dto.response.ShiftResponse;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final SiteRepository siteRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final AssignmentRepository assignmentRepository;

    public ShiftService(ShiftRepository shiftRepository,
                        SiteRepository siteRepository,
                        TenantRepository tenantRepository,
                        UserRoleRepository userRoleRepository,
                        AssignmentRepository assignmentRepository) {
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public ShiftResponse createShift(UUID tenantId, UUID siteId,
                                     CreateShiftRequest request,
                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("shifts:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to create shifts in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        String trimmedName = request.getName().trim();
        if (shiftRepository.existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNull(siteId, trimmedName)) {
            throw new DuplicateResourceException(
                    "Shift '" + trimmedName + "' already exists for this site");
        }

        Shift shift = Shift.builder()
                .siteId(siteId)
                .tenantId(tenantId)
                .name(trimmedName)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .allowOvernight(request.isAllowOvernight())
                .status("active")
                .createdBy(callerUserId)
                .build();

        shiftRepository.save(shift);
        log.info("Shift created: id={} siteId={} tenantId={} by={}", shift.getId(), siteId, tenantId, callerUserId);
        return toResponse(shift);
    }

    @Transactional
    public ShiftResponse configureOt(UUID tenantId, UUID siteId, UUID shiftId,
                                     ConfigureShiftOtRequest request,
                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("shifts:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update shifts in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Shift shift = shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(shiftId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found: " + shiftId));

        if (request.getAllowOvertime() == null
                && request.getEarlyCheckinMinutes() == null
                && request.getLateCheckoutMinutes() == null) {
            throw new IllegalArgumentException(
                    "At least one of allowOvertime, earlyCheckinMinutes, or lateCheckoutMinutes must be provided");
        }

        if (request.getAllowOvertime() != null)       shift.setAllowOvertime(request.getAllowOvertime());
        if (request.getEarlyCheckinMinutes() != null) shift.setEarlyCheckinMinutes(request.getEarlyCheckinMinutes());
        if (request.getLateCheckoutMinutes() != null) shift.setLateCheckoutMinutes(request.getLateCheckoutMinutes());

        shiftRepository.save(shift);
        log.info("Shift OT configured: shiftId={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
        return toResponse(shift);
    }

    @Transactional
    public ShiftResponse updateShift(UUID tenantId, UUID siteId, UUID shiftId,
                                     UpdateShiftRequest request,
                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("shifts:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update shifts in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Shift shift = shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(shiftId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found: " + shiftId));

        if (StringUtils.hasText(request.getName())) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(shift.getName())
                    && shiftRepository.existsBySiteIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
                            siteId, newName, shiftId)) {
                throw new DuplicateResourceException(
                        "Shift '" + newName + "' already exists for this site");
            }
            shift.setName(newName);
        }
        if (request.getStartTime() != null)     shift.setStartTime(request.getStartTime());
        if (request.getEndTime() != null)       shift.setEndTime(request.getEndTime());
        if (request.getAllowOvernight() != null) shift.setAllowOvernight(request.getAllowOvernight());
        if (StringUtils.hasText(request.getStatus())) shift.setStatus(request.getStatus());

        shiftRepository.save(shift);
        log.info("Shift updated: id={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
        return toResponse(shift);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShiftResponse> listShifts(UUID tenantId, UUID siteId,
                                                  String status, int page, int size,
                                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("shifts:list")) {
                throw new AccessDeniedException(
                        "You do not have permission to list shifts in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startTime"));
        Page<Shift> resultPage = StringUtils.hasText(status)
                ? shiftRepository.findBySiteIdAndTenantIdAndStatusAndDeletedAtIsNull(siteId, tenantId, status, pageable)
                : shiftRepository.findBySiteIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId, pageable);

        List<UUID> shiftIds = resultPage.getContent().stream().map(Shift::getId).toList();
        Map<UUID, Long> historyCounts = new HashMap<>();
        if (!shiftIds.isEmpty()) {
            for (AssignmentRepository.ShiftAssignmentCount row : assignmentRepository.countByShiftIdIn(shiftIds)) {
                historyCounts.put(row.getShiftId(), row.getCnt());
            }
        }

        return PageResponse.from(resultPage.map(s ->
                toResponse(s, historyCounts.getOrDefault(s.getId(), 0L))));
    }

    @Transactional
    public void deleteShift(UUID tenantId, UUID siteId, UUID shiftId,
                            UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("shifts:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to delete shifts in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Shift shift = shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(shiftId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found: " + shiftId));

        // Unlike Workspace/Site delete, this blocks on ANY assignment ever having referenced
        // the shift (not just currently-active ones) — deleting must never lose history.
        // Use "deactivate" (status=inactive) instead if the shift has ever been used.
        if (assignmentRepository.existsByShiftId(shiftId)) {
            throw new IllegalArgumentException(
                    "Shift '" + shift.getName() + "' has been used by at least one assignment and cannot be "
                            + "deleted — use deactivate instead to preserve history");
        }

        shift.setDeletedAt(java.time.OffsetDateTime.now());
        shiftRepository.save(shift);
        log.info("Shift deleted: id={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> findActiveShiftsForSite(UUID siteId) {
        return shiftRepository
                .findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active")
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ShiftResponse toResponse(Shift s) {
        return toResponse(s, assignmentRepository.countByShiftId(s.getId()));
    }

    private ShiftResponse toResponse(Shift s, long assignmentHistoryCount) {
        return ShiftResponse.builder()
                .id(s.getId())
                .siteId(s.getSiteId())
                .tenantId(s.getTenantId())
                .name(s.getName())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .allowOvernight(s.isAllowOvernight())
                .allowOvertime(s.isAllowOvertime())
                .earlyCheckinMinutes(s.getEarlyCheckinMinutes())
                .lateCheckoutMinutes(s.getLateCheckoutMinutes())
                .status(s.getStatus())
                .assignmentHistoryCount(assignmentHistoryCount)
                .canDelete(assignmentHistoryCount == 0)
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
