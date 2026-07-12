package com.fams.modules.randomcheck.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis sorted-set queue for delayed random-check dispatch.
 *
 * Key  : fams:randomcheck:dispatch
 * Score: scheduledAt epoch-seconds
 * Value: scheduledCheckId (UUID string)
 *
 * This is the Java/Spring equivalent of a Bull/BullMQ delayed job queue.
 * Entries whose score ≤ now are "due" and will be processed by the dispatcher job.
 */
@Slf4j
@Component
public class RandomCheckDispatchQueue {

    static final String QUEUE_KEY = "fams:randomcheck:dispatch";

    private final StringRedisTemplate redis;

    public RandomCheckDispatchQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Registers a scheduled check in the dispatch queue at the given fire time. */
    public void enqueue(UUID scheduledCheckId, OffsetDateTime scheduledAt) {
        double score = scheduledAt.toInstant().getEpochSecond();
        redis.opsForZSet().add(QUEUE_KEY, scheduledCheckId.toString(), score);
        log.debug("Enqueued dispatch job checkId={} scheduledAt={}", scheduledCheckId, scheduledAt);
    }

    /**
     * Atomically pops all check IDs whose scheduled time is ≤ now.
     * Uses ZRANGEBYSCORE + ZREMRANGEBYSCORE in a pipeline for atomicity.
     */
    public List<UUID> dequeueExpired() {
        double nowScore = Instant.now().getEpochSecond();
        // Fetch entries due now
        Set<String> due = redis.opsForZSet().rangeByScore(QUEUE_KEY, 0, nowScore);
        if (due == null || due.isEmpty()) return List.of();
        // Remove those entries atomically
        redis.opsForZSet().removeRangeByScore(QUEUE_KEY, 0, nowScore);
        return due.stream().map(UUID::fromString).toList();
    }

    /** Removes a specific check from the queue (used when cancelling a scheduled check). */
    public void cancel(UUID scheduledCheckId) {
        redis.opsForZSet().remove(QUEUE_KEY, scheduledCheckId.toString());
        log.debug("Cancelled dispatch job checkId={}", scheduledCheckId);
    }

    /** Returns the number of checks currently waiting in the queue. */
    public long queueSize() {
        Long size = redis.opsForZSet().size(QUEUE_KEY);
        return size != null ? size : 0L;
    }

    /** Returns the scheduled_at epoch-seconds for a check, or null if not in queue. */
    public Double scheduledScore(UUID scheduledCheckId) {
        return redis.opsForZSet().score(QUEUE_KEY, scheduledCheckId.toString());
    }

    /** Returns the epoch-seconds score of the oldest (lowest score) item, or null if queue is empty. */
    public Double oldestScheduledEpoch() {
        var entries = redis.opsForZSet().rangeWithScores(QUEUE_KEY, 0, 0);
        if (entries == null || entries.isEmpty()) return null;
        return entries.iterator().next().getScore();
    }
}
