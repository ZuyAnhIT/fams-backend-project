package com.fams.shared.monitoring;

import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component("randomCheckQueue")
public class RandomCheckQueueHealthIndicator extends AbstractHealthIndicator {

    private static final long LAG_THRESHOLD_SECONDS = 15 * 60L;

    private final RandomCheckDispatchQueue dispatchQueue;

    public RandomCheckQueueHealthIndicator(RandomCheckDispatchQueue dispatchQueue) {
        this.dispatchQueue = dispatchQueue;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long queueSize = dispatchQueue.queueSize();
        builder.withDetail("dispatch_queue_size", queueSize);

        Double oldestEpoch = dispatchQueue.oldestScheduledEpoch();
        if (oldestEpoch == null) {
            builder.up().withDetail("lag_seconds", 0);
            return;
        }

        long lagSeconds = Instant.now().getEpochSecond() - oldestEpoch.longValue();
        builder.withDetail("oldest_scheduled_at_epoch", oldestEpoch.longValue())
               .withDetail("lag_seconds", lagSeconds);

        if (lagSeconds > LAG_THRESHOLD_SECONDS) {
            builder.down().withDetail("reason",
                    "Dispatch queue has items overdue by " + lagSeconds + "s (threshold=" + LAG_THRESHOLD_SECONDS + "s)");
        } else {
            builder.up();
        }
    }
}
