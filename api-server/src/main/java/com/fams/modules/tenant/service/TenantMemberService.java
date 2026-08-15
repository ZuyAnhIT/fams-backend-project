package com.fams.modules.tenant.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.dto.response.TenantMemberResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Thành viên công ty" (Company Members) — 2026-08-14 user feedback: HR/Employee management
 * only ever covered people onboarded with an HR profile (department, position, hired date...),
 * but a Company Admin needs a broader view of literally everyone with ANY access to their
 * company — the owner, other admins, HR, site supervisors, field employees — including anyone
 * who holds a role but was never formally onboarded as an "Employee" (e.g. an owner who
 * received the company via {@link TenantService#transferOwner transfer-owner} and has no HR
 * record at all). This service merges {@code user_roles} (who has what access) with
 * {@code employees} (HR profile, best-effort) rather than requiring one to imply the other.
 */
@Slf4j
@Service
public class TenantMemberService {

    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    public TenantMemberService(TenantRepository tenantRepository,
                               UserRoleRepository userRoleRepository,
                               UserRepository userRepository,
                               EmployeeRepository employeeRepository) {
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional(readOnly = true)
    public List<TenantMemberResponse> listMembers(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            boolean isOwner = callerUserId.equals(tenant.getOwnerId());
            if (!isOwner
                    && !callerPermissions.contains("roles:read")
                    && !callerPermissions.contains("roles:update")
                    && !callerPermissions.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to view this tenant's members");
            }
        }

        List<UserRole> assignments = userRoleRepository.findAllWithRoleByTenantId(tenantId);

        // Group by userId — one person can hold several roles at once (see BulkAssignRoleModal/
        // transfer-owner, which both leave prior roles in place when granting a new one).
        Map<UUID, List<UserRole>> byUser = assignments.stream()
                .collect(Collectors.groupingBy(UserRole::getUserId, LinkedHashMap::new, Collectors.toList()));

        Map<UUID, User> usersById = userRepository.findAllById(byUser.keySet()).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, Employee> employeeByUserId = employeeRepository
                .findAllByTenantIdAndUserIdInAndDeletedAtIsNull(tenantId, byUser.keySet()).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        List<TenantMemberResponse> members = new ArrayList<>();
        for (Map.Entry<UUID, List<UserRole>> entry : byUser.entrySet()) {
            UUID userId = entry.getKey();
            List<UserRole> userAssignments = entry.getValue();
            User user = usersById.get(userId);
            Employee employee = employeeByUserId.get(userId);

            String displayName = employee != null
                    ? ((employee.getFirstName() != null ? employee.getFirstName() : "")
                        + " " + (employee.getLastName() != null ? employee.getLastName() : "")).trim()
                    : (user != null ? user.getDisplayName() : null);

            members.add(TenantMemberResponse.builder()
                    .userId(userId)
                    .displayName(displayName != null && !displayName.isBlank() ? displayName : null)
                    .contact(user != null ? (user.getEmail() != null ? user.getEmail() : user.getPhone()) : null)
                    .isOwner(userId.equals(tenant.getOwnerId()))
                    .roleNames(userAssignments.stream().map(ur -> ur.getRole().getName()).toList())
                    .userRoleIds(userAssignments.stream().map(UserRole::getId).toList())
                    .hasEmployeeProfile(employee != null)
                    .employeeId(employee != null ? employee.getId() : null)
                    .position(employee != null ? employee.getPosition() : null)
                    .department(employee != null ? employee.getDepartment() : null)
                    .memberSince(userAssignments.stream()
                            .map(UserRole::getCreatedAt)
                            .min(Comparator.naturalOrder())
                            .orElse(null))
                    .build());
        }

        // Owner first, then alphabetical by name — the person the company cares about
        // identifying fastest (who's in charge) should never be buried on page 2.
        members.sort(Comparator
                .comparing(TenantMemberResponse::isOwner).reversed()
                .thenComparing(m -> m.getDisplayName() != null ? m.getDisplayName() : "", String.CASE_INSENSITIVE_ORDER));

        return members;
    }
}
