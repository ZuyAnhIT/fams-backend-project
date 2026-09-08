package com.fams.modules.subscription.repository;

import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingOrderRepository
        extends JpaRepository<BillingOrder, UUID>, JpaSpecificationExecutor<BillingOrder> {

    @Query(value = "SELECT nextval('billing_order_code_seq')", nativeQuery = true)
    long nextOrderCode();

    boolean existsByTenantIdAndStatusIn(UUID tenantId, Collection<BillingOrderStatus> statuses);

    List<BillingOrder> findAllByTenantIdAndStatusIn(UUID tenantId, Collection<BillingOrderStatus> statuses);

    Page<BillingOrder> findAllByTenantId(UUID tenantId, Pageable pageable);

    Optional<BillingOrder> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BillingOrder o where o.orderCode = :orderCode")
    Optional<BillingOrder> findByOrderCodeForUpdate(@Param("orderCode") Long orderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BillingOrder o where o.id = :id")
    Optional<BillingOrder> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByPaymentReferenceAndIdNot(String paymentReference, UUID id);

    List<BillingOrder> findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<BillingOrderStatus> statuses, OffsetDateTime cutoff);
}
