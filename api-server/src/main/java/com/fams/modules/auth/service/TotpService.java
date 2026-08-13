package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.DisableTotpRequest;
import com.fams.modules.auth.dto.request.TotpVerifyRequest;
import com.fams.modules.auth.dto.response.TotpEnableResponse;
import com.fams.modules.auth.dto.response.TotpSetupResponse;
import com.fams.modules.auth.entity.TotpBackupCode;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.TotpBackupCodeRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TotpService {

    private static final String SETUP_PREFIX  = "totp:setup:";
    // Reverse index (userId -> current setupToken) so a repeat /totp/setup call can invalidate
    // the previous pending session — added 2026-08-12 (FE contract update): without this, calling
    // setup twice left TWO valid secrets alive at once (whichever QR/manual key the user scans
    // first still works), which is exactly the "multiple secrets coexisting" state the new
    // contract's security rules call out to avoid.
    private static final String SETUP_USER_INDEX_PREFIX = "totp:setup:user:";
    private static final int    SETUP_TTL_MIN = 10;
    private static final String BASE32_ALPHA  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String ISSUER        = "FAMS";
    private static final String BACKUP_CODE_ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I ambiguity
    private static final int    BACKUP_CODE_COUNT  = 8;
    private static final int    BACKUP_CODE_LENGTH = 8;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final TotpBackupCodeRepository backupCodeRepository;
    private final TotpSecretCipher secretCipher;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String baseUrl;
    private final AuditLogService auditLogService;

    public TotpService(StringRedisTemplate redis,
                       UserRepository userRepository,
                       TotpBackupCodeRepository backupCodeRepository,
                       TotpSecretCipher secretCipher,
                       BCryptPasswordEncoder passwordEncoder,
                       @Value("${app.base-url}") String baseUrl,
                       AuditLogService auditLogService) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.secretCipher = secretCipher;
        this.passwordEncoder = passwordEncoder;
        this.baseUrl = baseUrl;
        this.auditLogService = auditLogService;
    }

    // qrCodeUrl on the builder below is @Deprecated on the DTO (external clients shouldn't rely
    // on it going forward) but this is the one legitimate internal producer of it, kept for
    // backward compatibility on purpose — suppressed rather than silenced by removing the field.
    @SuppressWarnings("deprecation")
    public TotpSetupResponse initiateSetup(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // FE contract update (2026-08-12): an already-2FA'd account calling setup again must be
        // rejected, not silently issued a fresh secret — the account already has one enabled.
        if (user.isTotpEnabled()) {
            throw new DuplicateResourceException("TOTP is already enabled for this account");
        }

        // Invalidate any prior pending setup for this user before creating a new one — otherwise
        // two valid secrets could exist at once (an earlier QR/manual key the user never
        // finished scanning would silently still work after they reopened the setup screen and
        // got what looks like a fresh one).
        String userIndexKey = SETUP_USER_INDEX_PREFIX + userId;
        String priorToken = redis.opsForValue().get(userIndexKey);
        if (priorToken != null) {
            redis.delete(SETUP_PREFIX + priorToken);
        }

        String secret = generateSecret();
        String setupToken = UUID.randomUUID().toString();

        // account: email, else phone, else userId — in that priority order, per contract.
        String account = StringUtils.hasText(user.getEmail()) ? user.getEmail()
                : StringUtils.hasText(user.getPhone()) ? user.getPhone()
                : userId.toString();

        // Store "{userId}|{account}|{secret}" — read back by both the QR page and enableTotp().
        String redisValue = userId + "|" + account + "|" + secret;
        redis.opsForValue().set(SETUP_PREFIX + setupToken, redisValue, SETUP_TTL_MIN, TimeUnit.MINUTES);
        redis.opsForValue().set(userIndexKey, setupToken, SETUP_TTL_MIN, TimeUnit.MINUTES);

        String qrCodeUrl = baseUrl + "/api/v1/auth/totp/qr?token=" + setupToken;
        String otpauthUri = buildOtpauthUri(account, secret);
        // Computed immediately before/after the Redis writes above with the same TTL constant,
        // so this reflects the real expiry rather than an independently-guessed value.
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(SETUP_TTL_MIN);

        log.info("TOTP setup initiated for user id={}", userId);

        return TotpSetupResponse.builder()
                .setupToken(setupToken)
                .otpauthUri(otpauthUri)
                .manualEntryKey(secret)
                .qrCodeUrl(qrCodeUrl)
                .expiresAt(expiresAt)
                .build();
    }

    /** Builds the otpauth:// provisioning URI — shared by initiateSetup (new otpauthUri field)
     *  and buildQrPageHtml (the deprecated HTML QR page), so both are guaranteed to encode the
     *  same account/secret the same way rather than risking two slightly different
     *  implementations drifting apart. Label = "{issuer}:{account}", each URL-encoded separately
     *  and joined with a literal colon (colon is a valid unencoded path character per RFC 3986,
     *  and every otpauth-compatible authenticator app treats it as the issuer/account separator).
     *  secret is base32 and never needs encoding. */
    private String buildOtpauthUri(String account, String secret) {
        String encodedIssuer = uriEncode(ISSUER);
        String encodedAccount = uriEncode(account);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /** Percent-encodes for use in a URI path/query — URLEncoder alone encodes spaces as "+"
     *  (application/x-www-form-urlencoded), which is wrong outside an actual form body; "%20" is
     *  the correct encoding here and what every otpauth URI example in the wild uses. */
    private static String uriEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public String buildQrPageHtml(String setupToken) {
        String value = redis.opsForValue().get(SETUP_PREFIX + setupToken);
        if (value == null) {
            throw new IllegalArgumentException("Invalid or expired TOTP setup token");
        }

        String[] parts = value.split("\\|", 3);
        String account = parts[1];
        String secret  = parts[2];

        String otpauthUri = buildOtpauthUri(account, secret);

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
    public TotpEnableResponse enableTotp(UUID userId, TotpVerifyRequest request) {
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

        // Issue #5 (docs/issues/ISSUES.md): encrypt at rest — previously stored plaintext.
        user.setTotpSecret(secretCipher.encrypt(secret));
        user.setTotpEnabled(true);
        userRepository.save(user);
        redis.delete(key);
        redis.delete(SETUP_USER_INDEX_PREFIX + userId);

        List<String> backupCodes = regenerateBackupCodes(userId);

        log.info("TOTP enabled for user id={}", userId);
        recordTotpAudit(user, "TOTP_ENABLED");
        return TotpEnableResponse.builder().backupCodes(backupCodes).build();
    }

    /**
     * Issue #5 (docs/issues/ISSUES.md): disabling 2FA previously required only a valid
     * JWT — no password, no TOTP code, nothing proving the caller actually still controls
     * the second factor (or the account at all beyond having a live session token). Now
     * requires proof via password, a live TOTP code, or an unused backup code.
     */
    @Transactional
    public void disableTotp(UUID userId, DisableTotpRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isTotpEnabled()) {
            throw new IllegalStateException("TOTP is not enabled on this account");
        }

        if (!reauthenticate(user, request)) {
            throw new InvalidCredentialsException(
                    "Could not verify your identity — provide the correct password, a valid TOTP code, or an unused backup code");
        }

        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        userRepository.save(user);
        backupCodeRepository.deleteByUserId(userId);
        log.info("TOTP disabled for user id={}", userId);
        recordTotpAudit(user, "TOTP_DISABLED");
    }

    /** 2FA toggling is exactly the kind of security-sensitive action the audit trail exists for
     *  (audit 2026-08-05 — previously untracked). TOTP is account-level, not tied to any one
     *  tenant, so tenantId is left null here — same as AuthService's own login-audit call does
     *  for a user with no tenant roles yet. Best-effort: an audit-log failure must not roll back
     *  a real 2FA state change, same defensive stance already used at every other call site. */
    private void recordTotpAudit(User user, String action) {
        try {
            auditLogService.record(
                    null,
                    user.getId(),
                    user.getEmail() != null ? user.getEmail() : user.getPhone(),
                    "USER",
                    user.getId().toString(),
                    action,
                    null, null,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} userId={}: {}", action, user.getId(), ex.getMessage());
        }
    }

    private boolean reauthenticate(User user, DisableTotpRequest request) {
        if (StringUtils.hasText(request.getPassword())) {
            return user.getPasswordHash() != null && passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        }
        if (StringUtils.hasText(request.getCode())) {
            return verifyCode(secretCipher.decrypt(user.getTotpSecret()), request.getCode());
        }
        if (StringUtils.hasText(request.getBackupCode())) {
            return consumeBackupCode(user.getId(), request.getBackupCode());
        }
        return false;
    }

    /** Discards any previously-issued codes and generates a fresh set — used on (re-)enable. */
    private List<String> regenerateBackupCodes(UUID userId) {
        backupCodeRepository.deleteByUserId(userId);
        SecureRandom random = new SecureRandom();
        List<String> plainCodes = new ArrayList<>(BACKUP_CODE_COUNT);
        List<TotpBackupCode> entities = new ArrayList<>(BACKUP_CODE_COUNT);
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
            for (int j = 0; j < BACKUP_CODE_LENGTH; j++) {
                sb.append(BACKUP_CODE_ALPHA.charAt(random.nextInt(BACKUP_CODE_ALPHA.length())));
            }
            String code = sb.toString();
            plainCodes.add(code);
            entities.add(TotpBackupCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .build());
        }
        backupCodeRepository.saveAll(entities);
        return plainCodes;
    }

    /** Marks a backup code used (single-use) if it matches an unused one for this user. */
    boolean consumeBackupCode(UUID userId, String candidateCode) {
        List<TotpBackupCode> unused = backupCodeRepository.findByUserIdAndUsedAtIsNull(userId);
        for (TotpBackupCode backupCode : unused) {
            if (passwordEncoder.matches(candidateCode, backupCode.getCodeHash())) {
                backupCode.setUsedAt(OffsetDateTime.now());
                backupCodeRepository.save(backupCode);
                log.info("Backup code consumed for user id={}", userId);
                return true;
            }
        }
        return false;
    }

    // ── TOTP algorithm (RFC 6238 / RFC 4226) ────────────────────────────────

    private String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** Decrypts the user's stored (encrypted) secret and checks a live TOTP code against it. */
    public boolean verifyStoredCode(User user, String code) {
        return verifyCode(secretCipher.decrypt(user.getTotpSecret()), code);
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
