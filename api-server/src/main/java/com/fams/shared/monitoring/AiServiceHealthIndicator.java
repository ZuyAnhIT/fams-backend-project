package com.fams.shared.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Reachability check for fams-ai (Face ID enrollment, liveness, embeddings) — found missing via
 * audit (2026-08-06): Health Check story asked for "DB, Redis, Queue, Notification provider" and
 * the first 3 plus FCM (notification provider) were already covered by RedisHealthIndicator /
 * FcmHealthIndicator / Actuator's auto-configured DataSource indicator, but nothing reported
 * whether fams-ai itself was up — meaning an outage there (Face ID enroll/verify/checkin-photo
 * calls all fail) was invisible on /api/v1/platform/system-status until someone spotted a spike
 * of individual error logs.
 *
 * Hits fams-ai's unauthenticated GET /health (ai-service/app/routers/health.py) — a plain
 * liveness ping, not the same as the internal-secret-gated business endpoints, so no secret is
 * sent here and none is needed.
 */
@Component("aiService")
public class AiServiceHealthIndicator extends AbstractHealthIndicator {

    private final RestClient restClient;

    public AiServiceHealthIndicator(@Value("${app.ai.service.internal-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            String body = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
            builder.up().withDetail("response", body);
        } catch (Exception e) {
            builder.down().withDetail("error", e.getMessage());
        }
    }
}
