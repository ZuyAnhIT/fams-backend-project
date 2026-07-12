package com.fams.shared.monitoring;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component("redis")
public class RedisHealthIndicator extends AbstractHealthIndicator {

    private static final String FACE_VERIFY_QUEUE_KEY = "fams:ai:face_verify_jobs";
    private static final String DISPATCH_QUEUE_KEY = "fams:randomcheck:dispatch";

    private final StringRedisTemplate redisTemplate;

    public RedisHealthIndicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // Use execute to verify connectivity — any command will do
        redisTemplate.execute((RedisCallback<Void>) conn -> {
            conn.keyCommands().exists("fams:health:probe".getBytes());
            return null;
        });

        Long faceVerifyDepth = redisTemplate.opsForList().size(FACE_VERIFY_QUEUE_KEY);
        Long dispatchDepth = redisTemplate.opsForZSet().size(DISPATCH_QUEUE_KEY);

        builder.up()
               .withDetail("face_verify_queue_depth", faceVerifyDepth != null ? faceVerifyDepth : 0L)
               .withDetail("dispatch_queue_depth", dispatchDepth != null ? dispatchDepth : 0L);
    }
}
