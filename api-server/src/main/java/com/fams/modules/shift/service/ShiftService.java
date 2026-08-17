package com.fams.modules.shift.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.shift.dto.request.ConfigureShiftOtRequest;
import com.fams.modules.shift.dto.request.CreateShiftRequest;
import com.fams.modules.shift.dto.request.UpdateShiftRequest;
import com.fams.modules.shift.dto.response.ShiftResponse;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.shift.specification.ShiftSpecification;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
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
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final AuditLogService auditLogService;

    public ShiftService(ShiftRepository shiftRepository,
                        SiteRepository siteRepository,
                        TenantRepository tenantRepository,
                        UserRoleRepository userRoleRepository,
                        AssignmentRepository assignmentRepository,
                        AuditLogService auditLogService) {
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.assignmentRepository = assignmentRepository;
        this.auditLogService = auditLogService;
    }

    private void clearExistingDefaultShift(UUID siteId) {
        shiftRepository.findBySiteIdAndIsDefaultTrueAndDeletedAtIsNull(siteId)
                .ifPresent(existing -> {
                    existing.setDefault(false);
                    shiftRepository.saveAndFlush(existing);
                });
    }

    private Map<String, Object> shiftAuditSnapshot(Shift s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("siteId", s.getSiteId());
        map.put("name", s.getName());
        map.put("startTime", String.valueOf(s.getStartTime()));
        map.put("endTime", String.valueOf(s.getEndTime()));
        map.put("allowOvernight", s.isAllowOvernight());
        map.put("allowOvertime", s.isAllowOvertime());
        map.put("earlyCheckinMinutes", s.getEarlyCheckinMinutes());
        map.put("lateCheckoutMinutes", s.getLateCheckoutMinutes());
        map.put("graceMinutes", s.getGraceMinutes());
        map.put("maxOtMinutesPerDay", s.getMaxOtMinutesPerDay());
        map.put("maxOtMinutesPerWeek", s.getMaxOtMinutesPerWeek());
        map.put("status", s.getStatus());
        map.put("isDefault", s.isDefault());
        return map;
    }

    private void recordAudit(UUID tenantId, UUID actorId, UUID shiftId, String action,
                              Map<String, Object> before, Map<String, Object> after) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Shift", shiftId.toString(), action,
                    before, after,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log action={} shiftId={}: {}", action, shiftId, e.getMessage());
        }
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

        validateShiftTimes(request.getStartTime(), request.getEndTime(), request.isAllowOvernight());
        if (request.getCheckinPolicyOverride() != null) {
            validateCheckinPolicy(request.getCheckinPolicyOverride());
        }

        if (request.isDefaultShift()) {
            clearExistingDefaultShift(siteId);
        }

        Shift shift = Shift.builder()
                .siteId(siteId)
                .tenantId(tenantId)
                .name(trimmedName)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .allowOvernight(request.isAllowOvernight())
                .status("active")
                .checkinPolicyOverride(request.getCheckinPolicyOverride())
                .isDefault(request.isDefaultShift())
                .graceMinutes(request.getGraceMinutes())
                .createdBy(callerUserId)
                .build();

        shiftRepository.save(shift);
        log.info("Shift created: id={} siteId={} tenantId={} by={}", shift.getId(), siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, shift.getId(), "shift_created", null, shiftAuditSnapshot(shift));
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
                && request.getLateCheckoutMinutes() == null
                && request.getGraceMinutes() == null
                && request.getMaxOtMinutesPerDay() == null && !request.isClearMaxOtMinutesPerDay()
                && request.getMaxOtMinutesPerWeek() == null && !request.isClearMaxOtMinutesPerWeek()) {
            throw new IllegalArgumentException(
                    "At least one of allowOvertime, earlyCheckinMinutes, lateCheckoutMinutes, graceMinutes, "
                    + "maxOtMinutesPerDay/clearMaxOtMinutesPerDay, or maxOtMinutesPerWeek/clearMaxOtMinutesPerWeek "
                    + "must be provided");
        }

        Map<String, Object> before = shiftAuditSnapshot(shift);

        if (request.getAllowOvertime() != null)       shift.setAllowOvertime(request.getAllowOvertime());
        if (request.getEarlyCheckinMinutes() != null) shift.setEarlyCheckinMinutes(request.getEarlyCheckinMinutes());
        if (request.getLateCheckoutMinutes() != null) shift.setLateCheckoutMinutes(request.getLateCheckoutMinutes());
        if (request.getGraceMinutes() != null)        shift.setGraceMinutes(request.getGraceMinutes());

        // #60 (docs/api/backend-feature-audit-2026-08-07.md): clear-flag takes a back seat to an
        // explicit value in the same request — same "clearX wins only when no value given"
        // convention as UpdatePlanLimitsRequest/UpdateSubscriptionRequest.
        if (request.getMaxOtMinutesPerDay() != null) {
            shift.setMaxOtMinutesPerDay(request.getMaxOtMinutesPerDay());
        } else if (request.isClearMaxOtMinutesPerDay()) {
            shift.setMaxOtMinutesPerDay(null);
        }
        if (request.getMaxOtMinutesPerWeek() != null) {
            shift.setMaxOtMinutesPerWeek(request.getMaxOtMinutesPerWeek());
        } else if (request.isClearMaxOtMinutesPerWeek()) {
            shift.setMaxOtMinutesPerWeek(null);
        }

        shiftRepository.save(shift);
        log.info("Shift OT configured: shiftId={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, shiftId, "shift_ot_configured", before, shiftAuditSnapshot(shift));
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

        Map<String, Object> before = shiftAuditSnapshot(shift);

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

        if (request.isClearCheckinPolicyOverride()) {
            shift.setCheckinPolicyOverride(null);
        } else if (request.getCheckinPolicyOverride() != null) {
            validateCheckinPolicy(request.getCheckinPolicyOverride());
            shift.setCheckinPolicyOverride(request.getCheckinPolicyOverride());
        }

        if (request.getDefaultShift() != null) {
            if (request.getDefaultShift() && !shift.isDefault()) {
                clearExistingDefaultShift(siteId);
            }
            shift.setDefault(request.getDefaultShift());
        }

        validateShiftTimes(shift.getStartTime(), shift.getEndTime(), shift.isAllowOvernight());

        shiftRepository.save(shift);
        log.info("Shift updated: id={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, shiftId, "shift_updated", before, shiftAuditSnapshot(shift));
        return toResponse(shift);
    }

    /** startTime/endTime/allowOvernight must agree: a same-day shift (allowOvernight=false)
     *  needs startTime strictly before endTime, otherwise the shift's duration is zero or
     *  negative — a schedule an employee could never actually satisfy. An overnight shift needs
     *  the two times to actually differ (startTime==endTime would mean either a zero-length
     *  shift or a full 24h shift depending on interpretation — reject the ambiguity). */
    private void validateShiftTimes(java.time.LocalTime startTime, java.time.LocalTime endTime,
                                     boolean allowOvernight) {
        if (!allowOvernight && !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException(
                    "startTime must be before endTime for a same-day shift (set allowOvernight=true "
                            + "for a shift that spans midnight)");
        }
        if (allowOvernight && startTime.equals(endTime)) {
            throw new IllegalArgumentException("startTime and endTime must not be identical");
        }
    }

    private static final Set<String> VALID_CHECKIN_POLICIES = Set.of("gps_only", "gps_face", "gps_face_liveness");

    private void validateCheckinPolicy(String policy) {
        if (!VALID_CHECKIN_POLICIES.contains(policy)) {
            throw new IllegalArgumentException(
                    "checkinPolicyOverride must be one of " + VALID_CHECKIN_POLICIES + ", got '" + policy + "'");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ShiftResponse> listShifts(UUID tenantId, UUID siteId,
                                                  String status, String search, Boolean isDefault,
                                                  int page, int size,
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
        Page<Shift> resultPage = shiftRepository.findAll(
                ShiftSpecification.build(siteId, tenantId, status, isDefault, search), pageable);

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

        Map<String, Object> before = shiftAuditSnapshot(shift);
        shift.setDeletedAt(java.time.OffsetDateTime.now());
        shiftRepository.save(shift);
        log.info("Shift deleted: id={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, shiftId, "shift_deleted", before, null);
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
                .graceMinutes(s.getGraceMinutes())
                .maxOtMinutesPerDay(s.getMaxOtMinutesPerDay())
                .maxOtMinutesPerWeek(s.getMaxOtMinutesPerWeek())
                .status(s.getStatus())
                .defaultShift(s.isDefault())
                .checkinPolicyOverride(s.getCheckinPolicyOverride())
                .assignmentHistoryCount(assignmentHistoryCount)
                .canDelete(assignmentHistoryCount == 0)
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
