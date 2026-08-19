package com.fams.modules.tenant.service;

import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * #134/#135 (2026-08-19): "storage usage" was completely untracked anywhere in the system — no
 * DB column, no aggregation logic, only the plan's configured LIMIT existed
 * ({@code PlanLimits.maxStorageGb}), never compared against anything. Deliberately scoped down
 * (per user decision) to only what's cheaply computable today via S3/MinIO listing:
 *
 * <ul>
 *   <li>Explanation-evidence files ({@code explanation-evidence/{tenantId}/...} — already
 *   tenant-prefixed by {@code ExplanationEvidenceStorageService}) — a direct prefix scan.</li>
 *   <li>Avatars ({@code avatars/{userId}-...} — NOT tenant-prefixed, keyed by user only) —
 *   resolved by first listing every user who holds a role in this tenant, then matching S3 keys
 *   against that set.</li>
 * </ul>
 *
 * <p><b>Explicitly NOT included</b>: Face ID enrollment/check-in photos, which live in
 * {@code ai-service}'s own host-mounted storage volume — a different system entirely, not
 * reachable from this S3 client. This makes the reported figure a floor, not the true total; the
 * Web Admin UI must say so rather than implying completeness.
 *
 * <p>On-demand computation (a live {@code ListObjectsV2} call per request), not a maintained
 * running counter — acceptable for the current scale (per user decision, "lightweight" over
 * "exact"), but would need to become an update-on-upload/delete counter with its own column if
 * the avatar bucket or tenant count grows large enough that a full-bucket scan per detail-page
 * view becomes slow.
 */
@Slf4j
@Service
public class TenantStorageUsageService {

    private static final String EVIDENCE_PREFIX = "explanation-evidence/";
    private static final String AVATAR_PREFIX = "avatars/";
    /** Safety cap on how many avatar objects we'll scan in one call — a full unbounded bucket
     *  listing on every tenant-detail view is the kind of thing that quietly becomes a production
     *  incident; 5000 objects is far beyond what this system's current scale would ever have. */
    private static final int MAX_AVATAR_OBJECTS_SCANNED = 5000;

    private final S3Client s3Client;
    private final String bucket;
    private final UserRoleRepository userRoleRepository;

    /** No shared S3Client bean exists in this codebase — AvatarStorageService builds its own
     *  private instance rather than exposing one, to keep MinIO-outage resilience scoped to
     *  itself (see that class's constructor comment). Building a second instance here the same
     *  way, rather than risking that resilience behavior by refactoring it into a shared bean. */
    public TenantStorageUsageService(@Value("${app.s3.endpoint:}") String endpoint,
                                     @Value("${app.s3.region}") String region,
                                     @Value("${app.s3.bucket}") String bucket,
                                     @Value("${app.s3.access-key}") String accessKey,
                                     @Value("${app.s3.secret-key}") String secretKey,
                                     UserRoleRepository userRoleRepository) {
        this.bucket = bucket;
        this.userRoleRepository = userRoleRepository;

        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));
        if (StringUtils.hasText(endpoint)) {
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3Client = builder.build();
    }

    /** Returns the tenant's best-effort current storage usage in bytes (evidence + avatars only —
     *  see class javadoc for what's excluded). Never throws — a storage-listing failure must not
     *  break the tenant-detail page; returns 0 and logs a warning instead. */
    public long computeUsedBytes(UUID tenantId) {
        try {
            long evidenceBytes = sumByPrefix(EVIDENCE_PREFIX + tenantId + "/");
            long avatarBytes = sumAvatarBytesForTenant(tenantId);
            return evidenceBytes + avatarBytes;
        } catch (Exception e) {
            log.warn("Failed to compute storage usage for tenantId={}: {}", tenantId, e.getMessage());
            return 0L;
        }
    }

    private long sumByPrefix(String prefix) {
        long total = 0L;
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuationToken != null) requestBuilder.continuationToken(continuationToken);
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            for (S3Object object : response.contents()) {
                total += object.size();
            }
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return total;
    }

    private long sumAvatarBytesForTenant(UUID tenantId) {
        Set<String> tenantUserIdPrefixes = userRoleRepository.findAllWithRoleByTenantId(tenantId).stream()
                .map(UserRole::getUserId)
                .filter(java.util.Objects::nonNull)
                .map(userId -> AVATAR_PREFIX + userId + "-")
                .collect(Collectors.toSet());
        if (tenantUserIdPrefixes.isEmpty()) return 0L;

        long total = 0L;
        int scanned = 0;
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(AVATAR_PREFIX);
            if (continuationToken != null) requestBuilder.continuationToken(continuationToken);
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            for (S3Object object : response.contents()) {
                String key = object.key();
                if (tenantUserIdPrefixes.stream().anyMatch(key::startsWith)) {
                    total += object.size();
                }
            }
            scanned += response.contents().size();
            continuationToken = (response.isTruncated() && scanned < MAX_AVATAR_OBJECTS_SCANNED)
                    ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return total;
    }
}
