package com.fams.modules.auth.service;

import com.fams.shared.exception.InvalidOtpException;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies a Firebase phone-auth ID token server-side. The client performs the actual
 * OTP send/verify with Firebase directly; the backend only ever sees the resulting ID
 * token and must confirm it's genuine before trusting the phone number inside it.
 *
 * Shared by {@link FirebasePhoneLoginService} (phone login) and {@link RegisterService}
 * (phone registration, issue #1 in docs/issues/ISSUES.md) so both flows verify phone
 * ownership the same way instead of duplicating the Firebase Admin SDK call.
 */
@Slf4j
@Component
public class FirebasePhoneTokenVerifier {

    private static final String PHONE_NUMBER_CLAIM = "phone_number";

    /**
     * @return the verified phone number (E.164) carried by the token
     * @throws InvalidOtpException if the token is invalid/expired or isn't a phone-auth token
     * @throws ResponseStatusException(503) if Firebase isn't configured on this server
     */
    public String verifyAndExtractPhone(String idToken) {
        FirebaseToken decoded = verify(idToken);
        String phone = (String) decoded.getClaims().get(PHONE_NUMBER_CLAIM);
        if (phone == null || phone.isBlank()) {
            log.warn("Firebase ID token does not contain a phone_number claim — not a phone auth token");
            throw new InvalidOtpException();
        }
        return phone;
    }

    private FirebaseToken verify(String idToken) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.error("Firebase is not initialized — FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_JSON must be set");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Firebase authentication is not configured on this server");
        }
        try {
            return FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (FirebaseAuthException e) {
            log.warn("Firebase ID token verification failed: {}", e.getMessage());
            throw new InvalidOtpException();
        }
    }
}
