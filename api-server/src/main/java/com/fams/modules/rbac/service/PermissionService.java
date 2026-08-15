package com.fams.modules.rbac.service;

import com.fams.modules.rbac.dto.response.PermissionGroupResponse;
import com.fams.modules.rbac.dto.response.PermissionResponse;
import com.fams.modules.rbac.entity.Permission;
import com.fams.modules.rbac.repository.PermissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Transactional(readOnly = true)
    public List<PermissionGroupResponse> listGrouped() {
        // Only assignable permissions — see V92 migration + docs/reviews/backend/
        // rbac-role-permission-audit-2026-08-13.md mục 3: ~23 catalog entries have no
        // enforcement code reading them, so offering them here would let an admin tick a
        // permission that silently does nothing.
        List<Permission> all = permissionRepository.findByIsAssignableTrueOrderByResourceAscActionAsc();

        Map<String, List<PermissionResponse>> byResource = new LinkedHashMap<>();
        for (Permission p : all) {
            byResource
                    .computeIfAbsent(p.getResource(), k -> new ArrayList<>())
                    .add(PermissionResponse.builder()
                            .id(p.getId())
                            .name(p.getName())
                            .resource(p.getResource())
                            .action(p.getAction())
                            .description(p.getDescription())
                            .build());
        }

        List<PermissionGroupResponse> groups = new ArrayList<>();
        byResource.forEach((resource, perms) -> groups.add(
                PermissionGroupResponse.builder()
                        .resource(resource)
                        .permissionCount(perms.size())
                        .permissions(perms)
                        .build()));

        log.info("List permissions grouped: {} groups, {} total", groups.size(), all.size());
        return groups;
    }
}
