package com.fams.modules.tenant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * #08 (frontend integration testing 2026-09-03): a company logo could previously only be set
 * by pasting a pre-hosted image URL. This uploads an actual image file from the owner's device,
 * exactly like {@link com.fams.modules.auth.service.AvatarStorageService} does for user avatars
 * — same S3-compatible object storage (MinIO in dev, real AWS S3 in prod), same bucket, a
 * separate {@code logos/} key prefix.
 *
 * <p>Bucket creation is left to {@code AvatarStorageService} (it runs the same
 * {@code ApplicationReadyEvent} path); this service only (re)asserts a bucket policy that makes
 * BOTH {@code avatars/*} and {@code logos/*} publicly readable, so whichever of the two services
 * initialises last, the policy ends up complete.
 */
@Slf4j
@Component
public class TenantLogoStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/svg+xml");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "svg");
    private static final String KEY_PREFIX = "logos/";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucket;
    private final String publicUrl;
    private final boolean isLocalS3;

    public TenantLogoStorageService(@Value("${app.s3.endpoint:}") String endpoint,
                                    @Value("${app.s3.region}") String region,
                                    @Value("${app.s3.bucket}") String bucket,
                                    @Value("${app.s3.access-key}") String accessKey,
                                    @Value("${app.s3.secret-key}") String secretKey,
                                    @Value("${app.s3.public-url}") String publicUrl) {
        this.bucket = bucket;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;

        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        this.isLocalS3 = StringUtils.hasText(endpoint);
        if (isLocalS3) {
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3Client = builder.build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureLogoPrefixIsPublic() {
        if (!isLocalS3) {
            return; // real AWS buckets + policies are provisioned separately
        }
        try {
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": "*",
                        "Action": "s3:GetObject",
                        "Resource": ["arn:aws:s3:::%s/avatars*", "arn:aws:s3:::%s/logos*"]
                      }]
                    }
                    """.formatted(bucket, bucket);
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(bucket).policy(policy).build());
        } catch (Exception e) {
            log.error("Could not assert public-read policy for logos/* on bucket '{}' — company logo "
                    + "upload will still work but images may not be viewable until fixed. Cause: {}",
                    bucket, e.getMessage());
        }
    }

    /** Uploads the file and returns its publicly-reachable URL. Caller persists the URL onto
     *  the tenant and deletes any previous file. */
    public String store(UUID tenantId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Logo image must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Logo must be a JPEG, PNG, WEBP or SVG image");
        }

        String extension = extractExtension(file.getOriginalFilename(), contentType);
        String key = KEY_PREFIX + tenantId + "-" + System.currentTimeMillis() + "." + extension;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read logo file", e);
        }

        log.info("Logo stored for tenant {} at s3://{}/{}", tenantId, bucket, key);
        return publicUrl + "/" + key;
    }

    /** Best-effort cleanup of a previously-uploaded logo when it's being replaced/removed. */
    public void deleteIfManaged(String previousLogoUrl) {
        String prefix = publicUrl + "/" + KEY_PREFIX;
        if (!StringUtils.hasText(previousLogoUrl) || !previousLogoUrl.startsWith(prefix)) {
            return; // not one of our own uploads (e.g. an external URL) — leave it alone
        }
        String key = KEY_PREFIX + previousLogoUrl.substring(prefix.length());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("Could not delete previous logo object {}: {}", key, e.getMessage());
        }
    }

    private String extractExtension(String originalFilename, String contentType) {
        if (StringUtils.hasText(originalFilename) && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (ALLOWED_EXTENSIONS.contains(ext)) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "jpg";
        };
    }
}
