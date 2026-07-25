package com.fams.modules.rbac.service;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.service.EmailService;
import com.fams.modules.rbac.dto.request.AcceptPlatformInvitationRequest;
import com.fams.modules.rbac.dto.request.InvitePlatformStaffRequest;
import com.fams.modules.rbac.dto.response.PlatformInvitationResponse;
import com.fams.modules.rbac.dto.response.ValidatePlatformInvitationResponse;
import com.fams.modules.rbac.entity.PlatformInvitation;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.PlatformInvitationRepository;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.specification.PlatformInvitationSpecification;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidInvitationException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.JwtAuthFilter;
import com.fams.shared.security.JwtProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Onboards new FAMS internal staff by email — the platform-scope counterpart to
 * {@code EmployeeInvitationService}. Kept as a fully separate table/service (not a
 * {@code tenantId IS NULL} row in {@code employee_invitations}) because that table's
 * {@code tenant_id} column is NOT NULL and every existing query assumes a tenant context;
 * conflating the two would require touching every tenant-invitation query to handle a null
 * tenant, for a flow that is conceptually distinct anyway (joining the platform team, not a
 * customer's company).
 */
@Slf4j
@Service
public class PlatformInvitationService {

    private static final String DEFAULT_ROLE_NAME = "PLATFORM_STAFF";

    private final PlatformInvitationRepository invitationRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final String frontendUrl;
    private final int invitationExpiryDays;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public PlatformInvitationService(
            PlatformInvitationRepository invitationRepository,
            UserRoleRepository userRoleRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailService emailService,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.invitation.expiry-days:7}") int invitationExpiryDays,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.invitationRepository = invitationRepository;
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailService = emailService;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.frontendUrl = frontendUrl;
        this.invitationExpiryDays = invitationExpiryDays;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional(readOnly = true)
    public ValidatePlatformInvitationResponse validateInvitation(UUID token) {
        PlatformInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (!"pending".equals(invitation.getStatus())) {
            throw new InvalidInvitationException(
                    "Invitation is " + invitation.getStatus() + " and can no longer be accepted");
        }
        if (OffsetDateTime.now().isAfter(invitation.getExpiresAt())) {
            invitation.setStatus("expired");
            invitationRepository.save(invitation);
            throw new InvalidInvitationException("Invitation has expired");
        }

        boolean isExistingUser = userRepository.findByEmailAndDeletedAtIsNull(invitation.getEmail()).isPresent();

        return ValidatePlatformInvitationResponse.builder()
                .email(invitation.getEmail())
                .existingUser(isExistingUser)
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<PlatformInvitationResponse> listInvitations(String status, String email, int page, int size) {
        int clampedSize = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<PlatformInvitation> spec = PlatformInvitationSpecification.build(status, email);
        Page<PlatformInvitationResponse> resultPage = invitationRepository.findAll(spec, pageable)
                .map(inv -> toResponse(inv, false));
        return PageResponse.from(resultPage);
    }

    @Transactional
    public PlatformInvitationResponse sendInvitation(UUID callerUserId, InvitePlatformStaffRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        invitationRepository.findByEmailAndStatusAndDeletedAtIsNull(normalizedEmail, "pending")
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "A pending platform invitation already exists for " + normalizedEmail);
                });

        Role role = resolveRole(request.getRoleId());
        if (role.getTenantId() != null) {
            throw new IllegalArgumentException(
                    "Role " + role.getName() + " is tenant-owned and cannot be used for a platform-staff invitation");
        }

        PlatformInvitation invitation = PlatformInvitation.builder()
                .email(normalizedEmail)
                .token(UUID.randomUUID())
                .status("pending")
                .invitedBy(callerUserId)
                .roleId(role.getId())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .expiresAt(OffsetDateTime.now().plusDays(invitationExpiryDays))
                .build();

        invitation = invitationRepository.save(invitation);

        String acceptUrl = frontendUrl + "/accept-invite?type=platform&token=" + invitation.getToken();
        emailService.sendPlatformInvitationEmail(normalizedEmail, acceptUrl, invitationExpiryDays);

        log.info("Platform invitation sent: id={} email={} roleId={} by={}",
                invitation.getId(), normalizedEmail, role.getId(), callerUserId);

        return toResponse(invitation, true);
    }

    @Transactional
    public PlatformInvitationResponse cancelInvitation(UUID invitationId, UUID callerUserId) {
        PlatformInvitation invitation = invitationRepository.findByIdAndDeletedAtIsNull(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found: " + invitationId));

        if (!"pending".equals(invitation.getStatus())) {
            throw new InvalidInvitationException(
                    "Only pending invitations can be cancelled; current status is " + invitation.getStatus());
        }

        invitation.setStatus("cancelled");
        invitationRepository.save(invitation);

        log.info("Platform invitation cancelled: id={} by={}", invitationId, callerUserId);
        return toResponse(invitation, false);
    }

    @Transactional
    public LoginResponse acceptInvitation(AcceptPlatformInvitationRequest request) {
        PlatformInvitation invitation = invitationRepository.findByToken(request.getToken())
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (!"pending".equals(invitation.getStatus())) {
            throw new InvalidInvitationException(
                    "Invitation is " + invitation.getStatus() + " and can no longer be accepted");
        }
        if (OffsetDateTime.now().isAfter(invitation.getExpiresAt())) {
            invitation.setStatus("expired");
            invitationRepository.save(invitation);
            throw new InvalidInvitationException("Invitation has expired");
        }

        User user = userRepository.findByEmailAndDeletedAtIsNull(invitation.getEmail()).orElse(null);

        if (user == null) {
            if (!StringUtils.hasText(request.getPassword())) {
                throw new IllegalArgumentException("Password is required to create a new account");
            }
            String displayName = resolveDisplayName(request.getDisplayName(), invitation);
            user = User.builder()
                    .email(invitation.getEmail())
                    .passwordHash(passwordEncoder.encode(request.getPassword()))
                    .displayName(displayName)
                    .isActive(true)
                    .emailVerified(true)
                    .failedLoginAttempts(0)
                    .build();
            userRepository.save(user);
            log.info("New user created via platform invitation: userId={} email={}", user.getId(), user.getEmail());
        } else {
            List<UserRole> existing = userRoleRepository.findByUserIdAndRoleIdAndTenantIdIsNull(user.getId(), invitation.getRoleId());
            if (existing.stream().anyMatch(ur -> ur.getDeletedAt() == null)) {
                throw new DuplicateResourceException("User already holds this platform role");
            }
        }

        Role role = resolveRole(invitation.getRoleId());

        UserRole userRole = UserRole.builder()
                .userId(user.getId())
                .role(role)
                .tenantId(null)
                .assignedBy(invitation.getInvitedBy())
                .build();
        userRoleRepository.save(userRole);
        redis.delete(JwtAuthFilter.PERMS_CACHE_PREFIX + user.getId() + ":platform");

        invitation.setStatus("accepted");
        invitationRepository.save(invitation);

        log.info("Platform invitation accepted: invitationId={} userId={} roleId={}",
                invitation.getId(), user.getId(), role.getId());

        return issueTokens(user, request.getDeviceId(), role.getName());
    }

    private String resolveDisplayName(String requested, PlatformInvitation invitation) {
        if (StringUtils.hasText(requested)) return requested;
        String firstName = StringUtils.hasText(invitation.getFirstName()) ? invitation.getFirstName() : "";
        String lastName = StringUtils.hasText(invitation.getLastName()) ? invitation.getLastName() : "";
        String combined = (firstName + " " + lastName).trim();
        return combined.isEmpty() ? invitation.getEmail() : combined;
    }

    private Role resolveRole(UUID roleId) {
        if (roleId != null) {
            return roleRepository.findByIdAndDeletedAtIsNull(roleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        }
        return roleRepository.findByNameAndTenantIdIsNull(DEFAULT_ROLE_NAME)
                .orElseThrow(() -> new ResourceNotFoundException("Default " + DEFAULT_ROLE_NAME + " role not found"));
    }

    private LoginResponse issueTokens(User user, String deviceIdRaw, String roleName) {
        String deviceId = StringUtils.hasText(deviceIdRaw) ? deviceIdRaw : "unknown";
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), null, roleName);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawRefresh))
                .deviceId(deviceId)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }

    private PlatformInvitationResponse toResponse(PlatformInvitation inv, boolean includeToken) {
        return PlatformInvitationResponse.builder()
                .id(inv.getId())
                .email(inv.getEmail())
                .status(inv.getStatus())
                .invitedBy(inv.getInvitedBy())
                .roleId(inv.getRoleId())
                .firstName(inv.getFirstName())
                .lastName(inv.getLastName())
                .expiresAt(inv.getExpiresAt())
                .createdAt(inv.getCreatedAt())
                .updatedAt(inv.getUpdatedAt())
                .token(includeToken ? inv.getToken() : null)
                .build();
    }
}
