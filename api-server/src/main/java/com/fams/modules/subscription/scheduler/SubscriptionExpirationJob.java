package com.fams.modules.subscription.scheduler;

import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.service.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
public class SubscriptionExpirationJob {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantService tenantService;

    public SubscriptionExpirationJob(TenantSubscriptionRepository subscriptionRepository,
                                     TenantService tenantService) {
        this.subscriptionRepository = subscriptionRepository;
        this.tenantService = tenantService;
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void expireSubscriptions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<TenantSubscription> expired =
                subscriptionRepository.findAllByStatusAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now);

        log.info("SubscriptionExpirationJob: found {} subscription(s) to expire", expired.size());

        for (TenantSubscription sub : expired) {
            try {
                processExpiredSubscription(sub);
            } catch (Exception e) {
                log.error("SubscriptionExpirationJob: failed to process tenantId={} subscriptionId={}: {}",
                        sub.getTenantId(), sub.getId(), e.getMessage(), e);
            }
        }

        log.info("SubscriptionExpirationJob: done");
    }

    @Transactional
    public void processExpiredSubscription(TenantSubscription sub) {
        sub.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(sub);
        log.info("SubscriptionExpirationJob: subscription expired — tenantId={} planId={} subscriptionId={}",
                sub.getTenantId(), sub.getPlanId(), sub.getId());

        try {
            tenantService.suspendTenant(sub.getTenantId());
            log.info("SubscriptionExpirationJob: tenant suspended — tenantId={}", sub.getTenantId());
        } catch (IllegalStateException e) {
            log.warn("SubscriptionExpirationJob: tenant {} already suspended or cancelled, skipping suspend: {}",
                    sub.getTenantId(), e.getMessage());
        }
    }
}
