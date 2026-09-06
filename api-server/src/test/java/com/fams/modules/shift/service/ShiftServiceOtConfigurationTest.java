package com.fams.modules.shift.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.randomcheck.service.RandomCheckConfigService;
import com.fams.modules.randomcheck.service.ScheduledCheckGeneratorService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.shift.dto.request.ConfigureShiftOtRequest;
import com.fams.modules.shift.dto.response.ShiftResponse;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftServiceOtConfigurationTest {

    @Mock ShiftRepository shiftRepository;
    @Mock SiteRepository siteRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock AssignmentRepository assignmentRepository;
    @Mock AssignmentService assignmentService;
    @Mock AuditLogService auditLogService;
    @Mock RandomCheckConfigService randomCheckConfigService;
    @Mock ScheduledCheckGeneratorService scheduledCheckGeneratorService;

    private ShiftService service;
    private UUID tenantId;
    private UUID siteId;
    private UUID shiftId;
    private UUID actorId;
    private Shift shift;

    @BeforeEach
    void setUp() {
        service = new ShiftService(shiftRepository, siteRepository, tenantRepository,
                userRoleRepository, assignmentRepository, assignmentService, auditLogService,
                randomCheckConfigService, scheduledCheckGeneratorService);
        tenantId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        shiftId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        shift = Shift.builder()
                .id(shiftId).tenantId(tenantId).siteId(siteId).name("Ca tối")
                .startTime(LocalTime.of(17, 0)).endTime(LocalTime.of(21, 0))
                .allowOvertime(false).lateCheckoutMinutes(0).earlyCheckinMinutes(15)
                .graceMinutes(5).status("active").randomCheckPolicy("inherit")
                .manualCheckPolicy("inherit").createdBy(actorId)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId))
                .thenReturn(Optional.of(Tenant.builder().id(tenantId).build()));
        when(siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId))
                .thenReturn(Optional.of(Site.builder().id(siteId).tenantId(tenantId).build()));
        when(shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(shiftId, siteId, tenantId))
                .thenReturn(Optional.of(shift));
    }

    @Test
    void rejectsOvertimeWithoutPositiveCheckoutWindowUsingVietnameseBusinessError() {
        ConfigureShiftOtRequest request = new ConfigureShiftOtRequest();
        request.setAllowOvertime(true);
        request.setLateCheckoutMinutes(0);

        assertThatThrownBy(() -> service.configureOt(tenantId, siteId, shiftId, request, actorId, true))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("INVALID_OT_CHECKOUT_WINDOW");
                    assertThat(error.getUserMessage()).contains("phải lớn hơn 0 phút");
                });
        verify(shiftRepository, never()).save(shift);
    }

    @Test
    void enablesOvertimeWhenCheckoutWindowIsPositive() {
        ConfigureShiftOtRequest request = new ConfigureShiftOtRequest();
        request.setAllowOvertime(true);
        request.setLateCheckoutMinutes(120);
        when(assignmentRepository.countByShiftId(shiftId)).thenReturn(0L);

        ShiftResponse response = service.configureOt(tenantId, siteId, shiftId, request, actorId, true);

        assertThat(response.isAllowOvertime()).isTrue();
        assertThat(response.getLateCheckoutMinutes()).isEqualTo(120);
        verify(shiftRepository).save(shift);
    }
}
