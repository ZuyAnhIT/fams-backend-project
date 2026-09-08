package com.fams.modules.rbac.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.rbac.dto.request.BulkAssignRoleRequest;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoleServiceBulkLocalizationTest {

    @Mock UserRoleRepository userRoleRepository;
    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock TenantRepository tenantRepository;
    @Mock SiteRepository siteRepository;
    @Mock StringRedisTemplate redis;
    @Mock AuditLogService auditLogService;
    @Mock NotificationService notificationService;

    @Test
    void duplicateSystemRoleReturnsVietnameseBusinessMessageWithoutInternalUserId() {
        UUID callerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        Role employeeRole = Role.builder()
                .id(roleId).name("EMPLOYEE").isSystem(true).isPlatformRole(false).build();
        UserRole existing = UserRole.builder()
                .id(UUID.randomUUID()).userId(userId).tenantId(tenantId).role(employeeRole).build();

        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId))
                .thenReturn(Optional.of(Tenant.builder().id(tenantId).name("Công ty mẫu").build()));
        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(User.builder().id(userId).isPlatformAdmin(false).build()));
        when(roleRepository.findByIdAndDeletedAtIsNull(roleId)).thenReturn(Optional.of(employeeRole));
        when(userRoleRepository.findByUserIdAndRoleIdAndTenantId(userId, roleId, tenantId))
                .thenReturn(List.of(existing));

        UserRoleService service = new UserRoleService(
                userRoleRepository, userRepository, roleRepository, tenantRepository, siteRepository,
                redis, auditLogService, notificationService);
        BulkAssignRoleRequest request = new BulkAssignRoleRequest();
        request.setTenantId(tenantId);
        request.setRoleId(roleId);
        request.setUserIds(List.of(userId));

        var result = service.bulkAssignRole(callerId, true, request);

        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getResults().getFirst().getMessage())
                .isEqualTo("Nhân viên đã có vai trò \"Nhân viên\" trong công ty này.")
                .doesNotContain(userId.toString())
                .doesNotContain("EMPLOYEE");
    }
}
