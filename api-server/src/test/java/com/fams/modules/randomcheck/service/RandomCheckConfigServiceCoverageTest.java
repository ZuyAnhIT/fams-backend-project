package com.fams.modules.randomcheck.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.randomcheck.dto.request.CreateRandomCheckConfigRequest;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RandomCheckConfigServiceCoverageTest {

    private final RandomCheckConfigRepository configRepository = mock(RandomCheckConfigRepository.class);
    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final SiteScopeService siteScopeService = mock(SiteScopeService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private RandomCheckConfigService service;
    private CreateRandomCheckConfigRequest request;
    private UUID tenantId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        service = new RandomCheckConfigService(configRepository, userRoleRepository, siteRepository,
                shiftRepository, siteScopeService, auditLogService);
        tenantId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        request = new CreateRandomCheckConfigRequest();
        request.setChecksPerShift(3);
        request.setMinIntervalMinutes(10);
        request.setAllowedStartTime(LocalTime.of(8, 0));
        request.setAllowedEndTime(LocalTime.of(15, 0));
        request.setWindowMode("full_shift");
        request.setCheckMode("location_only");
        request.setApplicableRoles(List.of());
        request.setResponseWindowSeconds(300);
        request.setManualChecksAllowed(true);

        when(siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId))
                .thenReturn(Optional.of(Site.builder().id(siteId).tenantId(tenantId).build()));
        when(siteScopeService.isSiteAllowed(any(), any(), any(), any(Boolean.class))).thenReturn(true);
        when(configRepository.findBySite(tenantId, siteId)).thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(invocation -> {
            RandomCheckConfig config = invocation.getArgument(0);
            config.setId(UUID.randomUUID());
            return config;
        });
    }

    @Test
    void rejectsFullShiftPolicyWhenAShiftCannotFitTheRequestedSpacing() {
        when(shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active"))
                .thenReturn(List.of(shift("Ca ngắn", 8, 0, 8, 15, false, "inherit")));

        assertThatThrownBy(() -> service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ca ngắn");
    }

    @Test
    void rejectsCustomWindowThatWouldSilentlyMissAnEnabledShift() {
        request.setWindowMode("custom_window");
        request.setChecksPerShift(1);
        when(shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active"))
                .thenReturn(List.of(shift("Ca tối", 20, 0, 22, 0, false, "inherit")));

        assertThatThrownBy(() -> service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ca tối");
    }

    @Test
    void explicitlyDisabledShiftIsAValidDocumentedExclusion() {
        request.setWindowMode("custom_window");
        request.setChecksPerShift(1);
        when(shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active"))
                .thenReturn(List.of(shift("Ca không kiểm tra", 20, 0, 22, 0, false, "disabled")));

        var response = service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true);

        assertThat(response.getWindowMode()).isEqualTo("custom_window");
        assertThat(response.isManualChecksAllowed()).isTrue();
    }

    @Test
    void fullShiftPolicySupportsAnOvernightShift() {
        request.setChecksPerShift(4);
        request.setMinIntervalMinutes(60);
        when(shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active"))
                .thenReturn(List.of(shift("Ca đêm", 22, 0, 6, 0, true, "inherit")));

        var response = service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true);

        assertThat(response.getChecksPerShift()).isEqualTo(4);
    }

    @Test
    void fullShiftAcceptsHiddenCustomTimesAndStoresSafeDefaults() {
        request.setAllowedStartTime(null);
        request.setAllowedEndTime(null);
        when(shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active"))
                .thenReturn(List.of(shift("Ca ngày", 8, 0, 17, 0, false, "inherit")));

        var response = service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true);

        assertThat(response.getAllowedStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.getAllowedEndTime()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void customWindowStillRequiresBothTimes() {
        request.setWindowMode("custom_window");
        request.setAllowedStartTime(null);

        assertThatThrownBy(() -> service.createSiteOverride(
                tenantId, siteId, request, UUID.randomUUID(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires both");
    }

    @Test
    void rejectsAShiftEditThatWouldCreateACoverageGap() {
        RandomCheckConfig config = RandomCheckConfig.builder().tenantId(tenantId).siteId(siteId)
                .checksPerShift(3).minIntervalMinutes(10).allowedStartTime(LocalTime.of(8, 0))
                .allowedEndTime(LocalTime.of(15, 0)).windowMode("full_shift")
                .isActive(true).build();
        when(configRepository.findBySite(tenantId, siteId)).thenReturn(Optional.of(config));
        Shift edited = shift("Ca bị rút ngắn", 8, 0, 8, 15, false, "inherit");

        assertThatThrownBy(() -> service.assertShiftCompatible(tenantId, edited))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage gap");
    }

    @Test
    void allowsAnExplicitShiftExclusionEvenWhenTheParentWindowDoesNotFit() {
        RandomCheckConfig config = RandomCheckConfig.builder().tenantId(tenantId).siteId(siteId)
                .checksPerShift(3).minIntervalMinutes(10).allowedStartTime(LocalTime.of(8, 0))
                .allowedEndTime(LocalTime.of(15, 0)).windowMode("full_shift")
                .isActive(true).build();
        when(configRepository.findBySite(tenantId, siteId)).thenReturn(Optional.of(config));

        service.assertShiftCompatible(
                tenantId, shift("Ca không áp dụng", 8, 0, 8, 5, false, "disabled"));
    }

    private Shift shift(String name, int startHour, int startMinute, int endHour, int endMinute,
                        boolean overnight, String randomPolicy) {
        return Shift.builder().id(UUID.randomUUID()).tenantId(tenantId).siteId(siteId).name(name)
                .startTime(LocalTime.of(startHour, startMinute)).endTime(LocalTime.of(endHour, endMinute))
                .allowOvernight(overnight).status("active")
                .randomCheckPolicy(randomPolicy).manualCheckPolicy("inherit").build();
    }
}
