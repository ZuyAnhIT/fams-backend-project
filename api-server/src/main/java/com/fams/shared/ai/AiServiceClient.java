package com.fams.shared.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AiServiceClient {

    private static final String HEADER_SECRET = "X-Internal-Secret";

    private final RestClient restClient;
    private final String secret;

    public AiServiceClient(
            @Value("${app.ai.service.internal-url}") String baseUrl,
            @Value("${app.ai.internal-secret}") String secret) {
        this.secret = secret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public void enrollFace(UUID tenantId, UUID employeeId, List<MultipartFile> photos) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("tenant_id", tenantId.toString());
        body.add("employee_id", employeeId.toString());
        for (MultipartFile photo : photos) {
            try {
                final String filename = photo.getOriginalFilename() != null
                        ? photo.getOriginalFilename() : "photo.jpg";
                byte[] bytes = photo.getBytes();
                body.add("photos", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
            } catch (IOException e) {
                throw new AiServiceException("Failed to read photo bytes: " + e.getMessage());
            }
        }

        try {
            restClient.post()
                    .uri("/enroll")
                    .header(HEADER_SECRET, secret)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai enrollment rejected tenantId={} employeeId={} status={} body={}",
                    tenantId, employeeId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai enrolled tenantId={} employeeId={}", tenantId, employeeId);
    }

    public void approveFace(UUID tenantId, UUID employeeId) {
        try {
            restClient.post()
                    .uri("/enroll/{employeeId}/approve?tenant_id={tenantId}", employeeId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai approve rejected employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai approved enrollment employeeId={}", employeeId);
    }

    public void rejectFace(UUID tenantId, UUID employeeId, String reason) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("reason", reason);

        try {
            restClient.post()
                    .uri("/enroll/{employeeId}/reject?tenant_id={tenantId}", employeeId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai reject rejected employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai rejected enrollment employeeId={}", employeeId);
    }

    public void revokeFace(UUID tenantId, UUID employeeId) {
        try {
            restClient.delete()
                    .uri("/enroll/{employeeId}?tenant_id={tenantId}", employeeId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai revoke rejected employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai revoked employeeId={}", employeeId);
    }

    public void deleteEmbedding(UUID faceProfileId) {
        try {
            restClient.delete()
                    .uri("/embeddings/{faceProfileId}", faceProfileId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai delete embedding rejected faceProfileId={} status={}", faceProfileId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai deleted embedding faceProfileId={}", faceProfileId);
    }

    /** Age-based retention sweep of checkin/random-check selfies + liveness challenge frames on
     *  fams-ai's disk — see ai-service/app/routers/checkin_photo.py's POST /checkins/cleanup for
     *  exactly what's swept (and, just as importantly, what's deliberately NOT swept). System-wide,
     *  no tenant scoping — called weekly by DataRetentionJob. */
    @SuppressWarnings("unchecked")
    /** System-wide sweep, no tenant scoping — kept for callers that intentionally want the
     *  platform-wide default window applied to every tenant's photos in one call. */
    public Map<String, Object> cleanupOldCheckinPhotos(int olderThanDays) {
        try {
            return restClient.post()
                    .uri("/checkins/cleanup?older_than_days={days}", olderThanDays)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai checkin photo cleanup failed status={}", e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    /** #144 (2026-08-19): tenant-scoped sweep — checkin/liveness-challenge photos are stored on
     *  disk under {@code checkins/{tenantId}/} and {@code liveness_challenges/{tenantId}/}
     *  (confirmed in fams-ai's storage_service.py), so a per-tenant retention override can be
     *  honored without touching other tenants' files. */
    public Map<String, Object> cleanupOldCheckinPhotos(int olderThanDays, UUID tenantId) {
        try {
            return restClient.post()
                    .uri("/checkins/cleanup?older_than_days={days}&tenant_id={tenantId}", olderThanDays, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai checkin photo cleanup failed tenantId={} status={}", tenantId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    public FaceStatusDto getFaceStatus(UUID tenantId, UUID employeeId) {
        try {
            return restClient.get()
                    .uri("/status/{employeeId}?tenant_id={tenantId}", employeeId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .body(FaceStatusDto.class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai status failed employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> startLivenessChallenge(UUID tenantId, UUID employeeId, String purpose, UUID siteId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("tenant_id", tenantId.toString());
        body.add("employee_id", employeeId.toString());
        body.add("purpose", purpose);
        if (siteId != null) {
            body.add("site_id", siteId.toString());
        }

        try {
            return restClient.post()
                    .uri("/liveness-challenge")
                    .header(HEADER_SECRET, secret)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai start liveness challenge failed employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitLivenessChallengeFrames(UUID tenantId, UUID employeeId, UUID challengeId,
                                                              List<MultipartFile> frames) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("tenant_id", tenantId.toString());
        body.add("employee_id", employeeId.toString());
        for (MultipartFile frame : frames) {
            try {
                final String filename = frame.getOriginalFilename() != null ? frame.getOriginalFilename() : "frame.jpg";
                byte[] bytes = frame.getBytes();
                body.add("frames", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
            } catch (IOException e) {
                throw new AiServiceException("Failed to read frame bytes: " + e.getMessage());
            }
        }

        try {
            return restClient.post()
                    .uri("/liveness-challenge/{challengeId}/frames", challengeId)
                    .header(HEADER_SECRET, secret)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai submit liveness frames failed challengeId={} status={}", challengeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    public byte[] getPendingReviewPhoto(UUID tenantId, UUID employeeId) {
        try {
            return restClient.get()
                    .uri("/enroll/{employeeId}/pending-photo?tenant_id={tenantId}", employeeId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .body(byte[].class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai pending photo fetch failed employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    /** Mirrors getPendingReviewPhoto — the selfie submitted for a checkin/checkout or random-check
     *  response, saved unconditionally by worker.py's save_checkin_photo() keyed by whatever Java
     *  passed as the async face-verify job's source_id (CheckinRecord id or CheckResponse id).
     *  Permission/site-scope must already be enforced by the caller before invoking this. */
    public byte[] getCheckinPhoto(UUID tenantId, UUID sourceId) {
        try {
            return restClient.get()
                    .uri("/checkins/{sourceId}/photo?tenant_id={tenantId}", sourceId, tenantId)
                    .header(HEADER_SECRET, secret)
                    .retrieve()
                    .body(byte[].class);
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai checkin photo fetch failed sourceId={} status={}", sourceId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }
    }

    public void enrollFromChallenge(UUID tenantId, UUID employeeId, UUID challengeId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("tenant_id", tenantId.toString());
        body.add("employee_id", employeeId.toString());
        body.add("challenge_id", challengeId.toString());

        try {
            restClient.post()
                    .uri("/enroll-from-challenge")
                    .header(HEADER_SECRET, secret)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.warn("fams-ai enroll-from-challenge rejected employeeId={} status={}", employeeId, e.getStatusCode());
            throw new AiServiceException(e.getResponseBodyAsString(), e.getStatusCode().value());
        }

        log.info("fams-ai enrolled from challenge tenantId={} employeeId={} challengeId={}", tenantId, employeeId, challengeId);
    }
}
