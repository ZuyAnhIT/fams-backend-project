package com.fams.modules.auth.controller;

import com.fams.modules.auth.repository.HealthCheckRepository;
import com.fams.modules.auth.service.AuthService;
import com.fams.modules.auth.service.ChangePasswordService;
import com.fams.modules.auth.service.EmailVerificationService;
import com.fams.modules.auth.service.FirebasePhoneLoginService;
import com.fams.modules.auth.service.GoogleLoginService;
import com.fams.modules.auth.service.LoginTotpService;
import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.auth.service.PasswordResetService;
import com.fams.modules.auth.service.RefreshTokenService;
import com.fams.modules.auth.service.RegisterService;
import com.fams.modules.auth.service.TotpService;
import com.fams.modules.auth.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthControllerTokenLinkTest {

    private EmailVerificationService emailVerificationService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        emailVerificationService = mock(EmailVerificationService.class);
        controller = new AuthController(
                mock(HealthCheckRepository.class),
                mock(AuthService.class),
                mock(FirebasePhoneLoginService.class),
                mock(LogoutService.class),
                mock(RegisterService.class),
                mock(ChangePasswordService.class),
                mock(UserProfileService.class),
                emailVerificationService,
                mock(PasswordResetService.class),
                mock(TotpService.class),
                mock(LoginTotpService.class),
                mock(GoogleLoginService.class),
                mock(RefreshTokenService.class),
                "http://192.168.1.135:3000");
    }

    @Test
    void browserRegistrationLinkRedirectsBeforeConsumingToken() {
        ResponseEntity<?> response = controller.verifyEmail("registration-token", MediaType.TEXT_HTML_VALUE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("http://192.168.1.135:3000/verify-email?token=registration-token");
        verify(emailVerificationService, never()).verifyToken("registration-token");
    }

    @Test
    void browserEmailChangeLinkRedirectsToFriendlyUiBeforeConsumingToken() {
        ResponseEntity<?> response = controller.confirmEmailChange("email-change-token", MediaType.TEXT_HTML_VALUE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("http://192.168.1.135:3000/verify-email?token=email-change-token&mode=email-change");
        verify(emailVerificationService, never()).confirmEmailChange("email-change-token");
    }

    @Test
    void jsonClientKeepsOriginalVerificationApiContract() {
        ResponseEntity<?> response = controller.verifyEmail("registration-token", MediaType.APPLICATION_JSON_VALUE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(emailVerificationService).verifyToken("registration-token");
    }
}
