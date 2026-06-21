package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.TotpVerifyRequest;
import com.fams.modules.auth.dto.response.TotpSetupResponse;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TotpService {

    private static final String SETUP_PREFIX  = "totp:setup:";
    private static final int    SETUP_TTL_MIN = 10;
    private static final String BASE32_ALPHA  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String ISSUER        = "FAMS";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final String baseUrl;

    public TotpService(StringRedisTemplate redis,
                       UserRepository userRepository,
                       @Value("${app.base-url}") String baseUrl) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.baseUrl = baseUrl;
    }

    public TotpSetupResponse initiateSetup(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String secret = generateSecret();
        String setupToken = UUID.randomUUID().toString();

        // Store "{userId}|{email}|{secret}" so the QR endpoint can build the provisioning URI
        String emailOrId = (user.getEmail() != null) ? user.getEmail() : userId.toString();
        String redisValue = userId + "|" + emailOrId + "|" + secret;
        redis.opsForValue().set(SETUP_PREFIX + setupToken, redisValue, SETUP_TTL_MIN, TimeUnit.MINUTES);

        String qrCodeUrl = baseUrl + "/api/v1/auth/totp/qr?token=" + setupToken;
        log.info("TOTP setup initiated for user id={}", userId);

        return TotpSetupResponse.builder()
                .setupToken(setupToken)
                .qrCodeUrl(qrCodeUrl)
                .manualEntryKey(secret)
                .build();
    }

    public String buildQrPageHtml(String setupToken) {
        String value = redis.opsForValue().get(SETUP_PREFIX + setupToken);
        if (value == null) {
            throw new IllegalArgumentException("Invalid or expired TOTP setup token");
        }

        String[] parts = value.split("\\|", 3);
        String account = parts[1];
        String secret  = parts[2];

        String otpauthUri = "otpauth://totp/" + ISSUER + ":" + account
                + "?secret=" + secret + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=6&period=30";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>FAMS – Authenticator Setup</title>
                  <style>
                    body { font-family: sans-serif; display: flex; flex-direction: column;
                           align-items: center; padding: 40px; background: #f5f5f5; }
                    .card { background: white; border-radius: 12px; padding: 32px;
                            box-shadow: 0 2px 12px rgba(0,0,0,.1); text-align: center; }
                    #qr { margin: 24px auto; }
                    code { background: #eee; padding: 4px 8px; border-radius: 4px;
                           font-size: 14px; word-break: break-all; }
                    p { color: #555; font-size: 14px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>FAMS Authenticator Setup</h2>
                    <p>Scan the QR code below with <strong>Google Authenticator</strong>,
                       <strong>Authy</strong>, or any TOTP app.</p>
                    <div id="qr"></div>
                    <p>Or enter this key manually:</p>
                    <code>%s</code>
                    <p style="margin-top:24px;color:#888">
                      This page expires in 10 minutes. Do not share it.
                    </p>
                  </div>
                  <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"
                          crossorigin="anonymous"></script>
                  <script>
                    new QRCode(document.getElementById("qr"), {
                      text: "%s",
                      width: 256,
                      height: 256,
                      colorDark: "#000000",
                      colorLight: "#ffffff"
                    });
                  </script>
                </body>
                </html>
                """.formatted(secret, otpauthUri);
    }

    @Transactional
    public void enableTotp(UUID userId, TotpVerifyRequest request) {
        String key = SETUP_PREFIX + request.getSetupToken();
        String value = redis.opsForValue().get(key);
        if (value == null) {
            throw new IllegalArgumentException("Invalid or expired TOTP setup token");
        }

        String[] parts = value.split("\\|", 3);
        UUID storedUserId = UUID.fromString(parts[0]);
        String secret = parts[2];

        if (!storedUserId.equals(userId)) {
            throw new IllegalArgumentException("Setup token does not belong to this user");
        }

        if (!verifyCode(secret, request.getCode())) {
            throw new IllegalArgumentException("Invalid TOTP code — please try again");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setTotpSecret(secret);
        user.setTotpEnabled(true);
        userRepository.save(user);

        redis.delete(key);
        log.info("TOTP enabled for user id={}", userId);
    }

    @Transactional
    public void disableTotp(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        userRepository.save(user);
        log.info("TOTP disabled for user id={}", userId);
    }

    // ── TOTP algorithm (RFC 6238 / RFC 4226) ────────────────────────────────

    private String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    public boolean verifyCode(String base32Secret, String code) {
        try {
            byte[] secretBytes = base32Decode(base32Secret);
            long counter = System.currentTimeMillis() / 1000L / 30L;
            for (long step = counter - 1; step <= counter + 1; step++) {
                String expected = String.format("%06d", hotp(secretBytes, step));
                if (expected.equals(code)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("TOTP verification error", e);
            return false;
        }
    }

    private static int hotp(byte[] key, long counter) throws Exception {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(msg);
        int offset = hash[hash.length - 1] & 0x0F;
        int truncated = ((hash[offset]     & 0x7F) << 24)
                      | ((hash[offset + 1] & 0xFF) << 16)
                      | ((hash[offset + 2] & 0xFF) << 8)
                      |  (hash[offset + 3] & 0xFF);
        return truncated % 1_000_000;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHA.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHA.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        encoded = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] out = new byte[encoded.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (char c : encoded.toCharArray()) {
            int val = BASE32_ALPHA.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return java.util.Arrays.copyOf(out, idx);
    }
}
