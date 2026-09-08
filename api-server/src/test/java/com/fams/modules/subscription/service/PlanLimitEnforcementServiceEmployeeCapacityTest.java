package com.fams.modules.subscription.service;

import com.fams.modules.audit.repository.AuditLogRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.entity.PlanLimits;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.repository.PlanLimitsRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.shared.exception.PlanLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanLimitEnforcementServiceEmployeeCapacityTest {

    @Mock TenantSubscriptionRepository subscriptionRepository;
    @Mock PlanLimitsRepository planLimitsRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock SiteRepository siteRepository;
    @Mock ScheduledCheckRepository scheduledCheckRepository;
    @Mock AuditLogService auditLogService;
    @Mock AuditLogRepository auditLogRepository;

    @InjectMocks PlanLimitEnforcementService service;

    @Test
    void acceptsBatchThatExactlyFitsRemainingEmployeeCapacity() {
        UUID tenantId = UUID.randomUUID();
        prepareLimit(tenantId, 20, 18);

        assertThatCode(() -> service.assertEmployeeCapacity(tenantId, 2, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWholeBatchThatWouldCrossEmployeeLimit() {
        UUID tenantId = UUID.randomUUID();
        prepareLimit(tenantId, 20, 18);

        assertThatThrownBy(() -> service.assertEmployeeCapacity(tenantId, 3, UUID.randomUUID()))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("plan allows 20")
                .hasMessageContaining("requested additional 3");
    }

    private void prepareLimit(UUID tenantId, int maximum, long current) {
        UUID planId = UUID.randomUUID();
        when(subscriptionRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(TenantSubscription.builder().tenantId(tenantId).planId(planId).build()));
        when(planLimitsRepository.findByPlanId(planId))
                .thenReturn(Optional.of(PlanLimits.builder().planId(planId).maxEmployees(maximum).build()));
        when(employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(current);
    }
}
