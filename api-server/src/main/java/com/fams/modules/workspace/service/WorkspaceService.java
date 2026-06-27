package com.fams.modules.workspace.service;

import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.workspace.dto.request.CreateWorkspaceRequest;
import com.fams.modules.workspace.dto.request.UpdateWorkspaceRequest;
import com.fams.modules.workspace.dto.response.WorkspaceResponse;
import com.fams.modules.workspace.dto.response.WorkspaceTreeResponse;
import com.fams.modules.workspace.entity.Workspace;
import com.fams.modules.workspace.repository.WorkspaceRepository;
import com.fams.modules.workspace.specification.WorkspaceSpecification;
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

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkspaceService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "type", "status", "createdAt", "updatedAt");

    private final WorkspaceRepository workspaceRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            TenantRepository tenantRepository,
                            UserRoleRepository userRoleRepository) {
        this.workspaceRepository = workspaceRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional
    public WorkspaceResponse createWorkspace(UUID tenantId, CreateWorkspaceRequest request,
                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspaces:create")) {
                throw new AccessDeniedException("You do not have permission to create workspaces in this tenant");
            }
        }

        if (workspaceRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
                tenantId, request.getName().trim())) {
            throw new DuplicateResourceException(
                    "Workspace '" + request.getName().trim() + "' already exists in this tenant");
        }

        if (request.getParentId() != null) {
            workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.getParentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Parent workspace not found: " + request.getParentId()));
        }

        String type = (request.getType() != null) ? request.getType() : "department";

        Workspace workspace = Workspace.builder()
                .tenantId(tenantId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .type(type)
                .parentId(request.getParentId())
                .createdBy(callerUserId)
                .status("active")
                .build();

        workspaceRepository.save(workspace);
        log.info("Workspace created: id={} tenantId={} by={}", workspace.getId(), tenantId, callerUserId);
        return toResponse(workspace);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkspaceResponse> listWorkspaces(UUID tenantId, String search, String status,
                                                          String type, String sortBy, String sortDir,
                                                          int page, int size,
                                                          UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspaces:list")) {
                throw new AccessDeniedException("You do not have permission to list workspaces in this tenant");
            }
        }

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "name";
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        Specification<Workspace> spec = WorkspaceSpecification.build(tenantId, search, status, type);
        Page<WorkspaceResponse> resultPage = workspaceRepository.findAll(spec, pageable).map(this::toResponse);

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceTreeResponse> getWorkspaceTree(UUID tenantId, String search, String status,
                                                        UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspaces:list")) {
                throw new AccessDeniedException("You do not have permission to list workspaces in this tenant");
            }
        }

        // Fetch all non-deleted workspaces for this tenant, sorted by name
        List<Workspace> all = workspaceRepository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId);

        // Apply status filter
        if (StringUtils.hasText(status)) {
            all = all.stream().filter(w -> status.equals(w.getStatus())).collect(Collectors.toList());
        }

        // When search is provided, find matching nodes and include their ancestors
        Set<UUID> includedIds;
        if (StringUtils.hasText(search)) {
            String lowerSearch = search.toLowerCase();
            // Find directly matching nodes
            Set<UUID> matchingIds = all.stream()
                    .filter(w -> w.getName().toLowerCase().contains(lowerSearch))
                    .map(Workspace::getId)
                    .collect(Collectors.toSet());

            // Build index for ancestor lookup
            Map<UUID, Workspace> byId = all.stream().collect(Collectors.toMap(Workspace::getId, w -> w));

            // Include ancestors of matching nodes
            includedIds = new HashSet<>(matchingIds);
            for (UUID matchId : matchingIds) {
                UUID parentId = byId.containsKey(matchId) ? byId.get(matchId).getParentId() : null;
                while (parentId != null && byId.containsKey(parentId)) {
                    includedIds.add(parentId);
                    parentId = byId.get(parentId).getParentId();
                }
            }

            all = all.stream().filter(w -> includedIds.contains(w.getId())).collect(Collectors.toList());
        }

        return buildTree(all, null);
    }

    @Transactional
    public WorkspaceResponse updateWorkspace(UUID tenantId, UUID workspaceId, UpdateWorkspaceRequest request,
                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspaces:update")) {
                throw new AccessDeniedException("You do not have permission to update workspaces in this tenant");
            }
        }

        Workspace workspace = workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        if (StringUtils.hasText(request.getName())) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(workspace.getName())
                    && workspaceRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
                            tenantId, newName, workspaceId)) {
                throw new DuplicateResourceException(
                        "Workspace '" + newName + "' already exists in this tenant");
            }
            workspace.setName(newName);
        }

        if (request.getDescription() != null) {
            workspace.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        }

        if (StringUtils.hasText(request.getType())) {
            workspace.setType(request.getType());
        }

        if (StringUtils.hasText(request.getStatus())) {
            workspace.setStatus(request.getStatus());
        }

        // Handle parent change
        if (request.isClearParent()) {
            workspace.setParentId(null);
        } else if (request.getParentId() != null) {
            if (request.getParentId().equals(workspaceId)) {
                throw new IllegalArgumentException("A workspace cannot be its own parent");
            }
            // Ensure new parent exists and belongs to the same tenant
            workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.getParentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Parent workspace not found: " + request.getParentId()));

            // Guard against circular references: new parent must not be a descendant of this workspace
            if (isDescendant(workspaceId, request.getParentId(), tenantId)) {
                throw new IllegalArgumentException(
                        "Cannot set a descendant workspace as parent — circular reference detected");
            }
            workspace.setParentId(request.getParentId());
        }

        workspaceRepository.save(workspace);
        log.info("Workspace updated: id={} tenantId={} by={}", workspaceId, tenantId, callerUserId);
        return toResponse(workspace);
    }

    /** Returns true if candidateId is a descendant of ancestorId within the tenant. */
    private boolean isDescendant(UUID ancestorId, UUID candidateId, UUID tenantId) {
        List<Workspace> all = workspaceRepository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId);
        Map<UUID, UUID> parentById = all.stream()
                .filter(w -> w.getParentId() != null)
                .collect(Collectors.toMap(Workspace::getId, Workspace::getParentId));

        UUID current = parentById.get(candidateId);
        while (current != null) {
            if (current.equals(ancestorId)) return true;
            current = parentById.get(current);
        }
        return false;
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(UUID tenantId, UUID workspaceId,
                                          UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspaces:read")) {
                throw new AccessDeniedException("You do not have permission to view workspaces in this tenant");
            }
        }

        Workspace workspace = workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        return toResponse(workspace);
    }

    private List<WorkspaceTreeResponse> buildTree(List<Workspace> all, UUID parentId) {
        return all.stream()
                .filter(w -> Objects.equals(w.getParentId(), parentId))
                .map(w -> toTreeResponse(w, buildTree(all, w.getId())))
                .collect(Collectors.toList());
    }

    private WorkspaceTreeResponse toTreeResponse(Workspace w, List<WorkspaceTreeResponse> children) {
        return WorkspaceTreeResponse.builder()
                .id(w.getId())
                .tenantId(w.getTenantId())
                .name(w.getName())
                .description(w.getDescription())
                .type(w.getType())
                .parentId(w.getParentId())
                .status(w.getStatus())
                .createdBy(w.getCreatedBy())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .children(children)
                .build();
    }

    public WorkspaceResponse toResponse(Workspace w) {
        return WorkspaceResponse.builder()
                .id(w.getId())
                .tenantId(w.getTenantId())
                .name(w.getName())
                .description(w.getDescription())
                .type(w.getType())
                .parentId(w.getParentId())
                .status(w.getStatus())
                .createdBy(w.getCreatedBy())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}
