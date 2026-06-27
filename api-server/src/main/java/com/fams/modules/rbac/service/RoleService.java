package com.fams.modules.rbac.service;

import com.fams.modules.rbac.dto.request.CreateRoleRequest;
import com.fams.modules.rbac.dto.request.UpdateRoleRequest;
import com.fams.modules.rbac.dto.response.PermissionResponse;
import com.fams.modules.rbac.dto.response.RoleDetailResponse;
import com.fams.modules.rbac.dto.response.RoleResponse;
import com.fams.modules.rbac.entity.Permission;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.repository.PermissionRepository;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.specification.RoleSpecification;
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

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RoleService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "isSystem", "createdAt", "updatedAt");

    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;

    public RoleService(RoleRepository roleRepository,
                       TenantRepository tenantRepository,
                       PermissionRepository permissionRepository,
                       UserRoleRepository userRoleRepository) {
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<RoleResponse> listRoles(
            UUID tenantId,
            String search,
            Boolean isSystem,
            String sortBy,
            String sortDir,
            int page,
            int size,
            boolean callerIsPlatformAdmin) {

        if (tenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        }

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "isSystem";
        Sort sort = Sort.by(
                Sort.Order.desc("isSystem"),
                "asc".equalsIgnoreCase(sortDir)
                        ? Sort.Order.asc(resolvedSortBy)
                        : Sort.Order.desc(resolvedSortBy)
        );
        if ("isSystem".equals(resolvedSortBy)) {
            sort = Sort.by(Sort.Order.desc("isSystem"), Sort.Order.asc("name"));
        }

        Pageable pageable = PageRequest.of(page, size, sort);
        Specification<Role> spec = RoleSpecification.build(tenantId, search, isSystem);
        Page<RoleResponse> result = roleRepository.findAll(spec, pageable).map(this::toResponse);

        log.info("List roles: tenantId={} search={} isSystem={} page={} total={}",
                tenantId, search, isSystem, page, result.getTotalElements());

        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public RoleDetailResponse getRoleById(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (!role.isSystem() && role.getTenantId() != null) {
            if (!callerIsPlatformAdmin) {
                UUID tenantId = role.getTenantId();
                Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                if (!callerPermissions.contains("roles:read") && !callerPermissions.contains("roles:update")) {
                    throw new AccessDeniedException("You do not have permission to view roles in this tenant");
                }
            }
        }

        return toDetailResponse(role);
    }

    @Transactional
    public RoleDetailResponse createRole(UUID callerUserId, boolean callerIsPlatformAdmin, CreateRoleRequest request) {
        UUID tenantId = request.getTenantId();

        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:create")) {
                throw new AccessDeniedException("You do not have permission to create roles in this tenant");
            }
        }

        if (roleRepository.existsByTenantIdAndNameAndDeletedAtIsNull(tenantId, request.getName())) {
            throw new DuplicateResourceException(
                    "Role name '" + request.getName() + "' already exists in tenant " + tenantId);
        }

        Set<Permission> permissions = new HashSet<>();
        if (request.getPermissionIds() != null && !request.getPermissionIds().isEmpty()) {
            List<Permission> found = permissionRepository.findAllById(request.getPermissionIds());
            if (found.size() != request.getPermissionIds().size()) {
                Set<UUID> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
                List<UUID> missing = request.getPermissionIds().stream()
                        .filter(id -> !foundIds.contains(id))
                        .toList();
                throw new ResourceNotFoundException("Permissions not found: " + missing);
            }
            permissions.addAll(found);
        }

        Role role = Role.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .description(request.getDescription())
                .isSystem(false)
                .permissions(permissions)
                .build();

        role = roleRepository.save(role);

        log.info("Created custom role: id={} name={} tenantId={} permissionCount={} by userId={}",
                role.getId(), role.getName(), tenantId, permissions.size(), callerUserId);

        return toDetailResponse(role);
    }

    @Transactional
    public RoleDetailResponse updateRole(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin,
                                         UpdateRoleRequest request) {

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (role.isSystem()) {
            throw new IllegalArgumentException("System roles cannot be modified");
        }

        UUID tenantId = role.getTenantId();

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to update roles in this tenant");
            }
        }

        if (!role.getName().equals(request.getName()) &&
                roleRepository.existsByTenantIdAndNameAndDeletedAtIsNullAndIdNot(tenantId, request.getName(), roleId)) {
            throw new DuplicateResourceException(
                    "Role name '" + request.getName() + "' already exists in tenant " + tenantId);
        }

        Set<Permission> permissions = new HashSet<>();
        if (!request.getPermissionIds().isEmpty()) {
            List<Permission> found = permissionRepository.findAllById(request.getPermissionIds());
            if (found.size() != request.getPermissionIds().size()) {
                Set<UUID> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
                List<UUID> missing = request.getPermissionIds().stream()
                        .filter(id -> !foundIds.contains(id))
                        .toList();
                throw new ResourceNotFoundException("Permissions not found: " + missing);
            }
            permissions.addAll(found);
        }

        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);

        role = roleRepository.save(role);

        log.info("Updated custom role: id={} name={} tenantId={} permissionCount={} by userId={}",
                role.getId(), role.getName(), tenantId, permissions.size(), callerUserId);

        return toDetailResponse(role);
    }

    @Transactional
    public void deleteRole(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin) {

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (role.isSystem()) {
            throw new IllegalArgumentException("System roles cannot be deleted");
        }

        UUID tenantId = role.getTenantId();

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:delete")) {
                throw new AccessDeniedException("You do not have permission to delete roles in this tenant");
            }
        }

        if (userRoleRepository.existsByRoleIdAndDeletedAtIsNull(roleId)) {
            throw new IllegalArgumentException(
                    "Role is still assigned to one or more users. Revoke all assignments before deleting.");
        }

        role.setDeletedAt(OffsetDateTime.now());
        roleRepository.save(role);

        log.info("Deleted custom role: id={} name={} tenantId={} by userId={}",
                role.getId(), role.getName(), tenantId, callerUserId);
    }

    private RoleResponse toResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.isSystem())
                .tenantId(role.getTenantId())
                .permissionCount(role.getPermissions().size())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }

    private RoleDetailResponse toDetailResponse(Role role) {
        List<PermissionResponse> permResponses = role.getPermissions().stream()
                .map(p -> PermissionResponse.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .resource(p.getResource())
                        .action(p.getAction())
                        .description(p.getDescription())
                        .build())
                .toList();

        return RoleDetailResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.isSystem())
                .tenantId(role.getTenantId())
                .permissions(permResponses)
                .permissionCount(permResponses.size())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }
}
