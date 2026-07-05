package com.fams.shared.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class FaceVerifyJobPublisher {

    static final String QUEUE_KEY = "fams:ai:face_verify_jobs";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public FaceVerifyJobPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(UUID tenantId, UUID employeeId, UUID sourceId, String sourceType,
                        String faceImageBase64, boolean requiresLiveness) {
        Map<String, Object> job = new HashMap<>();
        job.put("tenant_id", tenantId.toString());
        job.put("employee_id", employeeId.toString());
        job.put("source_id", sourceId.toString());
        job.put("source_type", sourceType);
        job.put("face_image_base64", faceImageBase64);
        job.put("requires_liveness", requiresLiveness);

        try {
            String json = objectMapper.writeValueAsString(job);
            redisTemplate.opsForList().leftPush(QUEUE_KEY, json);
            log.info("Face verify job published: sourceType={} sourceId={} employeeId={} requiresLiveness={}",
                    sourceType, sourceId, employeeId, requiresLiveness);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize face verify job: {}", e.getMessage());
        }
    }
}
