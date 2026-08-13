package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AvatarStorageService avatarStorageService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private EmailService emailService;
    @Mock private PhoneOtpService phoneOtpService;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private AuditLogService auditLogService;

    @Test
    void requestEmailChangeBuildsFrontendUiLink() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("old@example.com")
                .displayName("Test User")
                .build();

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailAndDeletedAtIsNull("new@example.com")).thenReturn(Optional.empty());
        when(emailVerificationService.generateChangeToken(userId, "new@example.com"))
                .thenReturn("change-token");

        UserProfileService service = new UserProfileService(
                userRepository,
                avatarStorageService,
                emailVerificationService,
                emailService,
                phoneOtpService,
                userRoleRepository,
                auditLogService,
                "http://192.168.1.155:3000");

        service.requestEmailChange(userId, "NEW@example.com");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(
                org.mockito.ArgumentMatchers.eq("new@example.com"),
                url.capture());
        assertThat(url.getValue()).isEqualTo(
                "http://192.168.1.155:3000/verify-email?token=change-token&mode=email-change");
    }
}
