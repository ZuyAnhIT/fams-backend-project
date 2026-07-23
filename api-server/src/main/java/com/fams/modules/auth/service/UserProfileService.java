package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.UpdateProfileRequest;
import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.specification.UserSpecification;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final AvatarStorageService avatarStorageService;

    public UserProfileService(UserRepository userRepository, AvatarStorageService avatarStorageService) {
        this.userRepository = userRepository;
        this.avatarStorageService = avatarStorageService;
    }

    /**
     * Issue #4 (docs/issues/ISSUES.md): actual file upload for the avatar, as opposed to
     * {@link #updateProfile} which only accepts a pre-hosted URL string.
     */
    @Transactional
    public UserProfileResponse updateAvatarFile(UUID userId, MultipartFile file) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String previousUrl = user.getAvatarUrl();
        String newUrl = avatarStorageService.store(userId, file);
        user.setAvatarUrl(newUrl);
        userRepository.save(user);

        avatarStorageService.deleteIfManaged(previousUrl);

        log.info("Avatar file uploaded for user {}", userId);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserProfileResponse> searchUsers(String search, int page, int size) {
        int clampedSize = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.ASC, "email"));
        return PageResponse.from(
                userRepository.findAll(UserSpecification.build(search), pageable).map(this::toResponse));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        if (StringUtils.hasText(request.getPhone()) && !request.getPhone().equals(user.getPhone())) {
            userRepository.findByPhoneAndDeletedAtIsNull(request.getPhone()).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new DuplicateResourceException("Phone number is already in use");
                }
            });
            user.setPhone(request.getPhone());
        }

        if (StringUtils.hasText(request.getDisplayName())) {
            user.setDisplayName(request.getDisplayName());
        }

        // avatarUrl may be explicitly set to empty string to clear it; null means not provided
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl().isEmpty() ? null : request.getAvatarUrl());
        }

        // Issue #4 (docs/issues/ISSUES.md): hometown/gender/address follow the same
        // "blank means not provided" convention as displayName above; dateOfBirth has no
        // blank-string equivalent so any non-null value (validated @Past) is applied.
        if (StringUtils.hasText(request.getHometown())) {
            user.setHometown(request.getHometown());
        }
        if (StringUtils.hasText(request.getGender())) {
            user.setGender(request.getGender());
        }
        if (StringUtils.hasText(request.getAddress())) {
            user.setAddress(request.getAddress());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }

        userRepository.save(user);
        log.info("Profile updated for user {}", userId);

        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));
        return toResponse(user);
    }

    private UserProfileResponse toResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .dateOfBirth(user.getDateOfBirth())
                .hometown(user.getHometown())
                .gender(user.getGender())
                .address(user.getAddress())
                .googleLinked(user.getGoogleId() != null)
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
