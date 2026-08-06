package com.fams.modules.savedfilter.service;

import com.fams.modules.savedfilter.dto.request.CreateSavedFilterRequest;
import com.fams.modules.savedfilter.dto.request.UpdateSavedFilterRequest;
import com.fams.modules.savedfilter.dto.response.SavedFilterResponse;
import com.fams.modules.savedfilter.entity.SavedFilter;
import com.fams.modules.savedfilter.repository.SavedFilterRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Personal saved filters for large list screens (violations, checkins, employees, reports...).
 * Private per user — a saved filter is never visible to anyone but the user who created it,
 * matches the user story ("tôi muốn lưu filter"), same trust boundary as personal notification
 * settings (UserNotificationSettingService). Not shared/team-level in this version — if that
 * turns out to be a real need later, it's a different feature (a "shared" boolean plus a
 * tenant-wide read path), not a change to this one.
 */
@Slf4j
@Service
public class SavedFilterService {

    private final SavedFilterRepository savedFilterRepository;

    public SavedFilterService(SavedFilterRepository savedFilterRepository) {
        this.savedFilterRepository = savedFilterRepository;
    }

    @Transactional(readOnly = true)
    public List<SavedFilterResponse> list(UUID tenantId, UUID userId, String resourceType) {
        return savedFilterRepository
                .findAllByTenantIdAndUserIdAndResourceTypeAndDeletedAtIsNullOrderByNameAsc(
                        tenantId, userId, resourceType)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public SavedFilterResponse create(UUID tenantId, UUID userId, CreateSavedFilterRequest request) {
        String name = request.getName().trim();
        if (savedFilterRepository.existsByTenantIdAndUserIdAndResourceTypeAndNameIgnoreCaseAndDeletedAtIsNull(
                tenantId, userId, request.getResourceType(), name)) {
            throw new DuplicateResourceException(
                    "You already have a saved filter named \"" + name + "\" for " + request.getResourceType());
        }

        if (request.isDefaultFilter()) {
            clearExistingDefault(tenantId, userId, request.getResourceType());
        }

        SavedFilter saved = savedFilterRepository.save(SavedFilter.builder()
                .tenantId(tenantId)
                .userId(userId)
                .resourceType(request.getResourceType())
                .name(name)
                .filterParams(request.getFilterParams())
                .isDefault(request.isDefaultFilter())
                .build());
        log.info("Saved filter created: id={} tenantId={} userId={} resourceType={} name={}",
                saved.getId(), tenantId, userId, request.getResourceType(), name);
        return toResponse(saved);
    }

    @Transactional
    public SavedFilterResponse update(UUID tenantId, UUID userId, UUID filterId, UpdateSavedFilterRequest request) {
        SavedFilter filter = findOwned(tenantId, userId, filterId);

        if (request.getName() != null) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(filter.getName())
                    && savedFilterRepository.existsByTenantIdAndUserIdAndResourceTypeAndNameIgnoreCaseAndDeletedAtIsNull(
                            tenantId, userId, filter.getResourceType(), newName)) {
                throw new DuplicateResourceException(
                        "You already have a saved filter named \"" + newName + "\" for " + filter.getResourceType());
            }
            filter.setName(newName);
        }
        if (request.getFilterParams() != null) {
            filter.setFilterParams(request.getFilterParams());
        }
        if (request.getDefaultFilter() != null) {
            if (request.getDefaultFilter() && !filter.isDefault()) {
                clearExistingDefault(tenantId, userId, filter.getResourceType());
            }
            filter.setDefault(request.getDefaultFilter());
        }

        SavedFilter saved = savedFilterRepository.save(filter);
        log.info("Saved filter updated: id={} tenantId={} userId={}", filterId, tenantId, userId);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID tenantId, UUID userId, UUID filterId) {
        SavedFilter filter = findOwned(tenantId, userId, filterId);
        filter.setDeletedAt(OffsetDateTime.now());
        savedFilterRepository.save(filter);
        log.info("Saved filter deleted: id={} tenantId={} userId={}", filterId, tenantId, userId);
    }

    private SavedFilter findOwned(UUID tenantId, UUID userId, UUID filterId) {
        return savedFilterRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(filterId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Saved filter not found: " + filterId));
    }

    /** At most 1 default per user+resourceType (also enforced by a DB partial unique index as
     *  defense-in-depth against a race) — demote whatever currently holds it before promoting a
     *  new one. Uses saveAndFlush, not save: Hibernate's flush ordering runs ALL pending inserts
     *  before ANY pending updates in a transaction regardless of the order save() was called in
     *  Java, so a plain save() here would let the new row's INSERT (is_default=true) hit the
     *  partial unique index before this demotion's UPDATE actually reaches the database —
     *  confirmed by hitting exactly that "duplicate key value violates ... uq_saved_filters_one_default"
     *  error when testing this method without the explicit flush. */
    private void clearExistingDefault(UUID tenantId, UUID userId, String resourceType) {
        savedFilterRepository
                .findByTenantIdAndUserIdAndResourceTypeAndIsDefaultTrueAndDeletedAtIsNull(tenantId, userId, resourceType)
                .ifPresent(existing -> {
                    existing.setDefault(false);
                    savedFilterRepository.saveAndFlush(existing);
                });
    }

    private SavedFilterResponse toResponse(SavedFilter f) {
        return SavedFilterResponse.builder()
                .id(f.getId())
                .resourceType(f.getResourceType())
                .name(f.getName())
                .filterParams(f.getFilterParams())
                .defaultFilter(f.isDefault())
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }
}
