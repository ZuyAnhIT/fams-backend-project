package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.UpdateProfileRequest;
import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidCredentialsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Slf4j
@Service
public class UserProfileService {

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
