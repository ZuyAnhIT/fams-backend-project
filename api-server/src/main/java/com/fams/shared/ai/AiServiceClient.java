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
}
