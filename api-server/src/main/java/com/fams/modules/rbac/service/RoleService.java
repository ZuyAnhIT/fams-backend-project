package com.fams.modules.rbac.service;

import com.fams.modules.rbac.dto.response.RoleResponse;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.specification.RoleSpecification;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class RoleService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "isSystem", "createdAt", "updatedAt");

    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;

    public RoleService(RoleRepository roleRepository, TenantRepository tenantRepository) {
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
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
}
