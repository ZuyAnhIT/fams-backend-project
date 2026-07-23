package com.fams.modules.assignment.service;

import com.fams.modules.assignment.dto.request.CreateAssignmentRequest;
import com.fams.modules.assignment.dto.request.UpdateAssignmentRequest;
import com.fams.modules.assignment.dto.response.AssignmentResponse;
import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.specification.AssignmentSpecification;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.shift.repository.ShiftRepository;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AssignmentService {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("startDate", "endDate", "role", "status", "createdAt");

    private final AssignmentRepository assignmentRepository;
    private final SiteRepository siteRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final ScheduledCheckCancelService scheduledCheckCancelService;

    public AssignmentService(AssignmentRepository assignmentRepository,
                             SiteRepository siteRepository,
                             EmployeeRepository employeeRepository,
                             ShiftRepository shiftRepository,
                             TenantRepository tenantRepository,
                             UserRoleRepository userRoleRepository,
                             ScheduledCheckCancelService scheduledCheckCancelService) {
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.scheduledCheckCancelService = scheduledCheckCancelService;
    }

    @Transactional
    public AssignmentResponse createAssignment(UUID tenantId, UUID siteId,
                                               CreateAssignmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to create assignments in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.getEmployeeId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found: " + request.getEmployeeId()));

        if (request.getShiftId() != null) {
            shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                    request.getShiftId(), siteId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift not found for this site: " + request.getShiftId()));
        }

        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        if (request.getDaysOfWeek() != null && request.getDaysOfWeek().isEmpty()) {
            throw new IllegalArgumentException("daysOfWeek must not be empty; omit it to allow every day");
        }

        if (assignmentRepository.existsByEmployeeIdAndSiteIdAndStatusAndDeletedAtIsNull(
                request.getEmployeeId(), siteId, "active")) {
            throw new DuplicateResourceException(
                    "Employee already has an active assignment at this site");
        }

        Assignment assignment = Assignment.builder()
                .tenantId(tenantId)
                .siteId(siteId)
                .employeeId(request.getEmployeeId())
                .shiftId(request.getShiftId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .daysOfWeek(DayOfWeekBitmask.toBitmask(request.getDaysOfWeek()))
                .role(request.getRole() != null ? request.getRole() : "worker")
                .status("active")
                .notes(request.getNotes())
                .createdBy(callerUserId)
                .build();

        assignmentRepository.save(assignment);
        log.info("Assignment created: id={} employeeId={} siteId={} tenantId={} by={}",
                assignment.getId(), request.getEmployeeId(), siteId, tenantId, callerUserId);
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> listAssignments(UUID tenantId, UUID siteId,
                                                             String status, String role,
                                                             UUID employeeId, UUID shiftId,
                                                             String sortBy, String sortDir,
                                                             int page, int size,
                                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:list")) {
                throw new AccessDeniedException(
                        "You do not have permission to list assignments in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "startDate";
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        Specification<Assignment> spec = AssignmentSpecification.build(
                siteId, tenantId, StringUtils.hasText(status) ? status : null,
                StringUtils.hasText(role) ? role : null, employeeId, shiftId);
        Page<Assignment> resultPage = assignmentRepository.findAll(spec, pageable);

        return PageResponse.from(resultPage.map(this::toResponse));
    }

    @Transactional
    public AssignmentResponse updateAssignment(UUID tenantId, UUID siteId, UUID assignmentId,
                                               UpdateAssignmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update assignments in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Assignment assignment = assignmentRepository
                .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(assignmentId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + assignmentId));

        if (request.isClearShift()) {
            assignment.setShiftId(null);
        } else if (request.getShiftId() != null) {
            shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                    request.getShiftId(), siteId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift not found for this site: " + request.getShiftId()));
            assignment.setShiftId(request.getShiftId());
        }

        if (request.getStartDate() != null) assignment.setStartDate(request.getStartDate());

        if (request.isClearEndDate()) {
            assignment.setEndDate(null);
        } else if (request.getEndDate() != null) {
            assignment.setEndDate(request.getEndDate());
        }

        LocalDate effectiveEnd = assignment.getEndDate();
        if (effectiveEnd != null && effectiveEnd.isBefore(assignment.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        if (request.isClearDaysOfWeek()) {
            assignment.setDaysOfWeek(null);
        } else if (request.getDaysOfWeek() != null) {
            if (request.getDaysOfWeek().isEmpty()) {
                throw new IllegalArgumentException(
                        "daysOfWeek must not be empty; use clearDaysOfWeek to allow every day");
            }
            assignment.setDaysOfWeek(DayOfWeekBitmask.toBitmask(request.getDaysOfWeek()));
        }

        if (StringUtils.hasText(request.getRole())) assignment.setRole(request.getRole());
        if (request.getNotes() != null) assignment.setNotes(request.getNotes().isBlank() ? null : request.getNotes());

        assignmentRepository.save(assignment);
        log.info("Assignment updated: id={} siteId={} tenantId={} by={}",
                assignmentId, siteId, tenantId, callerUserId);
        return toResponse(assignment);
    }

    @Transactional
    public void cancelAssignment(UUID tenantId, UUID siteId, UUID assignmentId,
                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to cancel assignments in this tenant");
            }
        }

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Assignment assignment = assignmentRepository
                .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(assignmentId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + assignmentId));

        if ("cancelled".equals(assignment.getStatus())) {
            throw new IllegalArgumentException("Assignment is already cancelled");
        }

        assignment.setStatus("cancelled");
        assignmentRepository.save(assignment);
        log.info("Assignment cancelled: id={} siteId={} tenantId={} by={}",
                assignmentId, siteId, tenantId, callerUserId);

        int cancelled = scheduledCheckCancelService.cancelPendingByAssignment(assignmentId);
        if (cancelled > 0) {
            log.info("Auto-cancelled {} scheduled check(s) due to assignment cancellation id={}",
                    cancelled, assignmentId);
        }
    }

    @Transactional(readOnly = true)
    public long countActiveAssignmentsForSite(UUID siteId) {
        return assignmentRepository.countBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active");
    }

    public AssignmentResponse toResponse(Assignment a) {
        return AssignmentResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .siteId(a.getSiteId())
                .employeeId(a.getEmployeeId())
                .shiftId(a.getShiftId())
                .startDate(a.getStartDate())
                .endDate(a.getEndDate())
                .daysOfWeek(DayOfWeekBitmask.fromBitmask(a.getDaysOfWeek()))
                .role(a.getRole())
                .status(a.getStatus())
                .notes(a.getNotes())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
