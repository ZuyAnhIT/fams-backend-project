package com.fams.modules.employee.service;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.service.EmailService;
import com.fams.modules.employee.dto.request.AcceptInvitationRequest;
import com.fams.modules.employee.dto.request.InviteEmployeeRequest;
import com.fams.modules.employee.dto.response.InvitationResponse;
import com.fams.modules.employee.dto.response.ValidateInvitationResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.EmployeeInvitation;
import com.fams.modules.employee.repository.EmployeeInvitationRepository;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.employee.specification.EmployeeInvitationSpecification;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidInvitationException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.JwtProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class EmployeeInvitationService {

    private final EmployeeInvitationRepository invitationRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantRepository tenantRepository;
    private final EmailService emailService;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String appBaseUrl;
    private final int invitationExpiryDays;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public EmployeeInvitationService(
            EmployeeInvitationRepository invitationRepository,
            EmployeeRepository employeeRepository,
            UserRoleRepository userRoleRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            TenantRepository tenantRepository,
            EmailService emailService,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${app.base-url}") String appBaseUrl,
            @Value("${app.invitation.expiry-days:7}") int invitationExpiryDays,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.invitationRepository = invitationRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantRepository = tenantRepository;
        this.emailService = emailService;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.appBaseUrl = appBaseUrl;
        this.invitationExpiryDays = invitationExpiryDays;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional(readOnly = true)
    public ValidateInvitationResponse validateInvitation(UUID token) {
        EmployeeInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (!"pending".equals(invitation.getStatus())) {
            throw new InvalidInvitationException(
                    "Invitation is " + invitation.getStatus() + " and can no longer be used");
        }
        if (OffsetDateTime.now().isAfter(invitation.getExpiresAt())) {
            throw new InvalidInvitationException("Invitation has expired");
        }

        boolean isExistingUser = userRepository
                .findByEmailAndDeletedAtIsNull(invitation.getEmail()).isPresent();

        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(invitation.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        return ValidateInvitationResponse.builder()
                .email(invitation.getEmail())
                .isExistingUser(isExistingUser)
                .tenantName(tenant.getName())
                .build();
    }

    public PageResponse<InvitationResponse> listInvitations(UUID tenantId, String status, String email,
                                                             int page, int size,
                                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:read")) {
                throw new AccessDeniedException("You do not have permission to view invitations in this tenant");
            }
        }

        int clampedSize = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<EmployeeInvitation> spec = EmployeeInvitationSpecification.build(tenantId, status, email);
        Page<InvitationResponse> resultPage = invitationRepository.findAll(spec, pageable).map(this::toResponse);
        return PageResponse.from(resultPage);
    }

    @Transactional
    public InvitationResponse sendInvitation(UUID tenantId, InviteEmployeeRequest request,
                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to invite employees in this tenant");
            }
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        invitationRepository.findPendingByTenantAndEmail(tenantId, normalizedEmail)
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "A pending invitation already exists for " + normalizedEmail + " in this tenant");
                });

        EmployeeInvitation invitation = EmployeeInvitation.builder()
                .tenantId(tenantId)
                .email(normalizedEmail)
                .token(UUID.randomUUID())
                .status("pending")
                .invitedBy(callerUserId)
                .roleId(request.getRoleId())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .expiresAt(OffsetDateTime.now().plusDays(invitationExpiryDays))
                .build();

        invitation = invitationRepository.save(invitation);

        String acceptUrl = appBaseUrl + "/api/v1/invitations/accept?token=" + invitation.getToken();
        emailService.sendInvitationEmail(normalizedEmail, acceptUrl, invitationExpiryDays);

        log.info("Invitation sent: id={} email={} tenantId={} by={}", invitation.getId(), normalizedEmail, tenantId, callerUserId);

        return toResponse(invitation);
    }

    @Transactional
    public InvitationResponse cancelInvitation(UUID tenantId, UUID invitationId,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to cancel invitations in this tenant");
            }
        }

        EmployeeInvitation invitation = invitationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(invitationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found: " + invitationId));

        if (!"pending".equals(invitation.getStatus())) {
            throw new InvalidInvitationException(
                    "Only pending invitations can be cancelled; current status is " + invitation.getStatus());
        }

        invitation.setStatus("cancelled");
        invitationRepository.save(invitation);

        log.info("Invitation cancelled: id={} tenantId={} by={}", invitationId, tenantId, callerUserId);
        return toResponse(invitation);
    }

    @Transactional
    public LoginResponse acceptInvitation(AcceptInvitationRequest request) {
        EmployeeInvitation invitation = invitationRepository.findByToken(request.getToken())
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

        User user = userRepository.findByEmailAndDeletedAtIsNull(invitation.getEmail())
                .orElse(null);

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
            log.info("New user created via invitation: userId={} email={}", user.getId(), user.getEmail());
        } else {
            List<UserRole> existingRoles = userRoleRepository
                    .findActiveByUserIdAndTenantId(user.getId(), invitation.getTenantId());
            if (!existingRoles.isEmpty()) {
                throw new DuplicateResourceException("User is already a member of this tenant");
            }
        }

        Role role = resolveRole(invitation.getRoleId());

        UserRole userRole = UserRole.builder()
                .userId(user.getId())
                .role(role)
                .tenantId(invitation.getTenantId())
                .assignedBy(invitation.getInvitedBy())
                .build();
        userRoleRepository.save(userRole);

        invitation.setStatus("accepted");
        invitationRepository.save(invitation);

        // Create employee profile if not already linked for this user+tenant
        boolean hasProfile = employeeRepository
                .existsByTenantIdAndUserIdAndDeletedAtIsNull(invitation.getTenantId(), user.getId());
        if (!hasProfile) {
            Employee employee = Employee.builder()
                    .tenantId(invitation.getTenantId())
                    .userId(user.getId())
                    .firstName(StringUtils.hasText(invitation.getFirstName()) ? invitation.getFirstName() : "Unknown")
                    .lastName(StringUtils.hasText(invitation.getLastName()) ? invitation.getLastName() : "")
                    .email(invitation.getEmail())
                    .status("active")
                    .build();
            employeeRepository.save(employee);
            log.info("Employee profile created via invitation: employeeId={} userId={} tenantId={}",
                    employee.getId(), user.getId(), invitation.getTenantId());
        }

        log.info("Invitation accepted: invitationId={} userId={} tenantId={}",
                invitation.getId(), user.getId(), invitation.getTenantId());

        return issueTokens(user, request.getDeviceId(), invitation.getTenantId(), role.getName());
    }

    private String resolveDisplayName(String requested, EmployeeInvitation invitation) {
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
        return roleRepository.findByNameAndTenantIdIsNull("EMPLOYEE")
                .orElseThrow(() -> new ResourceNotFoundException("Default EMPLOYEE role not found"));
    }

    private LoginResponse issueTokens(User user, String deviceIdRaw, UUID tenantId, String roleName) {
        String deviceId = StringUtils.hasText(deviceIdRaw) ? deviceIdRaw : "unknown";
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, false, tenantId, roleName);

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

    private InvitationResponse toResponse(EmployeeInvitation inv) {
        return InvitationResponse.builder()
                .id(inv.getId())
                .tenantId(inv.getTenantId())
                .email(inv.getEmail())
                .status(inv.getStatus())
                .invitedBy(inv.getInvitedBy())
                .roleId(inv.getRoleId())
                .firstName(inv.getFirstName())
                .lastName(inv.getLastName())
                .expiresAt(inv.getExpiresAt())
                .createdAt(inv.getCreatedAt())
                .updatedAt(inv.getUpdatedAt())
                .build();
    }
}
