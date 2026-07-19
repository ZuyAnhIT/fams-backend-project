package com.fams.modules.employee.service;

import com.fams.modules.employee.dto.request.CreateDepartmentRequest;
import com.fams.modules.employee.dto.request.UpdateDepartmentRequest;
import com.fams.modules.employee.dto.response.DepartmentResponse;
import com.fams.modules.employee.entity.Department;
import com.fams.modules.employee.repository.DepartmentRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;

    public DepartmentService(DepartmentRepository departmentRepository,
                             TenantRepository tenantRepository,
                             UserRoleRepository userRoleRepository) {
        this.departmentRepository = departmentRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:read") && !perms.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to list departments in this tenant");
            }
        }

        return departmentRepository.findAllByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public DepartmentResponse createDepartment(UUID tenantId, CreateDepartmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to manage departments in this tenant");
            }
        }

        String name = request.getName().trim();
        departmentRepository.findByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)
                .ifPresent(d -> { throw new DuplicateResourceException("Department '" + name + "' already exists in this tenant"); });

        Department dept = Department.builder()
                .tenantId(tenantId)
                .name(name)
                .description(request.getDescription())
                .build();
        departmentRepository.save(dept);
        log.info("Department created: id={} tenantId={} by={}", dept.getId(), tenantId, callerUserId);
        return toResponse(dept);
    }

    @Transactional
    public DepartmentResponse updateDepartment(UUID tenantId, UUID departmentId, UpdateDepartmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:update")) {
                throw new AccessDeniedException("You do not have permission to manage departments in this tenant");
            }
        }

        Department dept = departmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(departmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));

        if (StringUtils.hasText(request.getName())) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(dept.getName())) {
                departmentRepository.findByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, newName)
                        .ifPresent(d -> { throw new DuplicateResourceException("Department '" + newName + "' already exists in this tenant"); });
            }
            dept.setName(newName);
        }
        if (request.getDescription() != null) {
            dept.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        }

        departmentRepository.save(dept);
        log.info("Department updated: id={} tenantId={} by={}", departmentId, tenantId, callerUserId);
        return toResponse(dept);
    }

    @Transactional
    public void deleteDepartment(UUID tenantId, UUID departmentId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("employees:delete")) {
                throw new AccessDeniedException("You do not have permission to manage departments in this tenant");
            }
        }

        Department dept = departmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(departmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));

        dept.setDeletedAt(OffsetDateTime.now());
        departmentRepository.save(dept);
        log.info("Department deleted: id={} tenantId={} by={}", departmentId, tenantId, callerUserId);
    }

    public DepartmentResponse toResponse(Department d) {
        return DepartmentResponse.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .name(d.getName())
                .description(d.getDescription())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
