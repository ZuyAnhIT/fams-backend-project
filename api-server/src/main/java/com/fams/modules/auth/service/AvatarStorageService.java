package com.fams.modules.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #4 (docs/issues/ISSUES.md): "đẩy ảnh từ máy cá nhân" — lets a user upload an
 * actual image file for their avatar instead of only accepting a pre-hosted URL.
 *
 * Backed by S3-compatible object storage (AWS SDK v2's {@link S3Client}), the same way
 * most real systems handle user-uploaded media: local dev/self-hosted deployments point
 * this at a MinIO container (see docker-compose.dev.yml — set app.s3.endpoint),
 * production points it at real AWS S3 (leave app.s3.endpoint empty) — both go through
 * the exact same code path, only configuration differs. This replaced an earlier local-
 * disk version of this class once the user confirmed they wanted cloud-storage-style
 * behavior to make real deployment easier later.
 */
@Slf4j
@Component
public class AvatarStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final String KEY_PREFIX = "avatars/";

    private final S3Client s3Client;
    private final String bucket;
    private final String publicUrl;

    public AvatarStorageService(@Value("${app.s3.endpoint:}") String endpoint,
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

        boolean isLocalS3 = StringUtils.hasText(endpoint);
        if (isLocalS3) {
            // MinIO/other self-hosted S3-compatible endpoints need path-style addressing
            // (bucket.example.com virtual-hosted style doesn't work without real DNS/TLS setup).
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3Client = builder.build();

        if (isLocalS3) {
            ensureBucketExists(); // convenience for local dev only — real AWS buckets are provisioned separately
        }
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created avatar storage bucket: {}", bucket);
        }
        // Avatars are meant to be publicly viewable (same as any other system's user
        // avatars). A bucket policy — not a per-object ACL — is the modern, portable way
        // to do this: many real S3 accounts have ACLs disabled entirely ("Bucket owner
        // enforced"), and MinIO doesn't honor the `public-read` canned ACL either, so a
        // policy is what actually works on both.
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/%s*"
                  }]
                }
                """.formatted(bucket, KEY_PREFIX);
        s3Client.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(bucket).policy(policy).build());
    }

    /**
     * Uploads the file and returns its publicly-reachable URL. Caller (UserProfileService)
     * is responsible for persisting the URL onto the user and deleting any previous file.
     */
    public String store(UUID userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Avatar file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Avatar must be a JPEG, PNG, or WEBP image");
        }

        String extension = extractExtension(file.getOriginalFilename(), contentType);
        String key = KEY_PREFIX + userId + "-" + System.currentTimeMillis() + "." + extension;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read avatar file", e);
        }

        log.info("Avatar stored for user {} at s3://{}/{}", userId, bucket, key);
        return publicUrl + "/" + key;
    }

    /** Best-effort cleanup of a previously-uploaded avatar when it's being replaced. */
    public void deleteIfManaged(String previousAvatarUrl) {
        String prefix = publicUrl + "/" + KEY_PREFIX;
        if (!StringUtils.hasText(previousAvatarUrl) || !previousAvatarUrl.startsWith(prefix)) {
            return; // not one of our own uploads (e.g. an external CDN URL) — leave it alone
        }
        String key = KEY_PREFIX + previousAvatarUrl.substring(prefix.length());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("Could not delete previous avatar object {}: {}", key, e.getMessage());
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
            default -> "jpg";
        };
    }
}
