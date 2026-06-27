package com.fams.modules.subscription.service;

import com.fams.modules.subscription.dto.request.CreatePlanRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanRequest;
import com.fams.modules.subscription.dto.response.PlanResponse;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class PlanService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("sortOrder", "name", "displayName", "priceMonthly", "priceYearly", "createdAt", "updatedAt");

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> listPlans(boolean activeOnly, String sortBy, String sortDir, int page, int size) {
        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "sortOrder";
        Sort sort = "desc".equalsIgnoreCase(sortDir)
                ? Sort.by(resolvedSortBy).descending()
                : Sort.by(resolvedSortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Plan> plans = activeOnly
                ? planRepository.findByIsActiveTrueAndDeletedAtIsNull(pageable)
                : planRepository.findByDeletedAtIsNull(pageable);
        return PageResponse.from(plans.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(UUID id) {
        Plan plan = planRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + id));
        return toResponse(plan);
    }

    @Transactional
    public PlanResponse createPlan(CreatePlanRequest request) {
        if (planRepository.findByNameAndDeletedAtIsNull(request.getName()).isPresent()) {
            throw new DuplicateResourceException("Plan name '" + request.getName() + "' already exists");
        }

        Plan plan = Plan.builder()
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .priceMonthly(request.getPriceMonthly())
                .priceYearly(request.getPriceYearly())
                .sortOrder(request.getSortOrder())
                .build();

        planRepository.save(plan);
        log.info("Plan created: id={} name={}", plan.getId(), plan.getName());
        return toResponse(plan);
    }

    @Transactional
    public PlanResponse updatePlan(UUID id, UpdatePlanRequest request) {
        Plan plan = planRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + id));

        if (StringUtils.hasText(request.getDisplayName())) plan.setDisplayName(request.getDisplayName());
        if (request.getDescription() != null)              plan.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        if (request.getPriceMonthly() != null)             plan.setPriceMonthly(request.getPriceMonthly());
        if (request.getPriceYearly() != null)              plan.setPriceYearly(request.getPriceYearly());
        if (request.getSortOrder() != null)                plan.setSortOrder(request.getSortOrder());
        if (request.getIsActive() != null)                 plan.setActive(request.getIsActive());

        planRepository.save(plan);
        log.info("Plan updated: id={} name={}", id, plan.getName());
        return toResponse(plan);
    }

    private PlanResponse toResponse(Plan plan) {
        return PlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .displayName(plan.getDisplayName())
                .description(plan.getDescription())
                .priceMonthly(plan.getPriceMonthly())
                .priceYearly(plan.getPriceYearly())
                .sortOrder(plan.getSortOrder())
                .isActive(plan.isActive())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }
}
