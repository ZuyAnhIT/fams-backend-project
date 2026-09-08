package com.fams.modules.subscription.scheduler;

import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.repository.BillingOrderRepository;
import com.fams.modules.subscription.service.BillingOrderService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.EnumSet;

@Slf4j
@Component
public class BillingReconciliationJob {

    public static final String JOB_NAME = "BillingReconciliationJob";
    private static final EnumSet<BillingOrderStatus> RECONCILABLE = EnumSet.of(
            BillingOrderStatus.CREATING, BillingOrderStatus.PENDING,
            BillingOrderStatus.PROCESSING, BillingOrderStatus.UNDERPAID);

    private final BillingOrderRepository orderRepository;
    private final BillingOrderService billingOrderService;
    private final ScheduledJobMonitor jobMonitor;

    public BillingReconciliationJob(BillingOrderRepository orderRepository,
                                    BillingOrderService billingOrderService,
                                    ScheduledJobMonitor jobMonitor) {
        this.orderRepository = orderRepository;
        this.billingOrderService = billingOrderService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedDelayString = "${app.billing.reconciliation-delay-ms:300000}")
    public void reconcilePendingOrders() {
        long startedAt = System.currentTimeMillis();
        try {
            var orders = orderRepository.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                    RECONCILABLE, OffsetDateTime.now().minusMinutes(2));
            for (var order : orders) {
                try {
                    billingOrderService.reconcile(order.getId());
                } catch (Exception ex) {
                    log.warn("Billing reconciliation failed orderId={} orderCode={}: {}",
                            order.getId(), order.getOrderCode(), ex.getMessage());
                }
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("Billing reconciliation job failed: {}", ex.getMessage(), ex);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, ex);
        }
    }
}
