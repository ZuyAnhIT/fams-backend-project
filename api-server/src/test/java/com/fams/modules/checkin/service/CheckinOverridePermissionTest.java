package com.fams.modules.checkin.service;

import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.checkin.dto.request.OverrideCheckinRequest;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.employee.repository.LivenessChallengeRepository;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.ai.FaceVerifyJobPublisher;
import com.fams.shared.storage.ExplanationEvidenceStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckinOverridePermissionTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock AssignmentService assignmentService;
    @Mock SiteRepository siteRepository;
    @Mock ShiftRepository shiftRepository;
    @Mock GeofenceRepository geofenceRepository;
    @Mock CheckinRepository checkinRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock SiteScopeService siteScopeService;
    @Mock AttendanceSummaryService attendanceSummaryService;
    @Mock FaceVerifyJobPublisher faceVerifyJobPublisher;
    @Mock FaceProfileRepository faceProfileRepository;
    @Mock LivenessChallengeRepository livenessChallengeRepository;
    @Mock ExplanationEvidenceStorageService evidenceStorageService;
    @Mock AuditLogService auditLogService;
    @Mock NotificationService notificationService;

    private CheckinService service;

    @BeforeEach
    void setUp() {
        service = new CheckinService(
                employeeRepository, assignmentService, siteRepository, shiftRepository,
                geofenceRepository, checkinRepository, userRoleRepository, siteScopeService,
                attendanceSummaryService, faceVerifyJobPublisher, faceProfileRepository,
                livenessChallengeRepository, evidenceStorageService, auditLogService,
                notificationService);
    }

    @Test
    void listPermissionAloneCannotOverrideCheckin() {
        UUID tenantId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();
        UUID checkinId = UUID.randomUUID();
        OverrideCheckinRequest request = new OverrideCheckinRequest();
        request.setStatus("valid");
        request.setReason("Kiểm thử phân quyền");

        when(userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId))
                .thenReturn(Set.of("checkins:list"));

        assertThatThrownBy(() -> service.overrideCheckin(
                tenantId, checkinId, request, callerUserId, false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("permission to override");

        verify(checkinRepository, never()).findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId);
    }
}
