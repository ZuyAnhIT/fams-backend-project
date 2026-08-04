package com.fams.shared.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Private object storage for employee explanation evidence. Objects are never
 * added to the public avatar bucket policy and are only read through an
 * authenticated tenant-scoped controller endpoint. */
@Slf4j
@Component
public class ExplanationEvidenceStorageService {

    public static final String MARKER_PREFIX = "evidence://";
    private static final String KEY_PREFIX = "explanation-evidence/";
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final S3Client s3Client;
    private final String bucket;

    public ExplanationEvidenceStorageService(@Value("${app.s3.endpoint:}") String endpoint,
                                             @Value("${app.s3.region}") String region,
                                             @Value("${app.s3.bucket}") String bucket,
                                             @Value("${app.s3.access-key}") String accessKey,
                                             @Value("${app.s3.secret-key}") String secretKey) {
        this.bucket = bucket;
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (StringUtils.hasText(endpoint)) {
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3Client = builder.build();
    }

    public String store(UUID tenantId, String sourceType, UUID recordId, MultipartFile file) {
        byte[] bytes = validateAndRead(file);
        String key = KEY_PREFIX + tenantId + "/" + sourceType + "/" + recordId;
        String contentType = file.getContentType().toLowerCase(Locale.ROOT);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .metadata(java.util.Map.of("tenant-id", tenantId.toString(), "source-type", sourceType))
                        .build(),
                RequestBody.fromBytes(bytes));
        log.info("Explanation evidence stored tenantId={} sourceType={} recordId={} size={}",
                tenantId, sourceType, recordId, bytes.length);
        return MARKER_PREFIX + key;
    }

    public StoredEvidence load(String marker) {
        if (!StringUtils.hasText(marker) || !marker.startsWith(MARKER_PREFIX + KEY_PREFIX)) {
            throw new IllegalArgumentException("Explanation evidence is not managed by FAMS storage");
        }
        String key = marker.substring(MARKER_PREFIX.length());
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new StoredEvidence(object.asByteArray(), object.response().contentType());
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Evidence image is required");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("Evidence image must not exceed 5MB");
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Evidence must be a JPEG, PNG, or WEBP image");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!matchesSignature(bytes, contentType)) {
                throw new IllegalArgumentException("Evidence file content does not match its image type");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read evidence image", e);
        }
    }

    private boolean matchesSignature(byte[] bytes, String contentType) {
        if (bytes.length < 12) return false;
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47;
            case "image/webp" -> bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    public record StoredEvidence(byte[] bytes, String contentType) {}
}
