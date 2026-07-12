package com.fams.modules.notification.controller;

import com.fams.modules.notification.dto.request.RegisterDeviceRequest;
import com.fams.modules.notification.dto.response.UserDeviceResponse;
import com.fams.modules.notification.entity.UserDevice;
import com.fams.modules.notification.service.UserDeviceService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.fams.shared.util.MaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Devices", description = "Device token management for FCM push notifications")
@RestController
@RequestMapping("/api/v1/me/devices")
public class UserDeviceController {

  private final UserDeviceService userDeviceService;

  public UserDeviceController(UserDeviceService userDeviceService) {
    this.userDeviceService = userDeviceService;
  }

  @Operation(
      summary = "Register device token",
      description =
          "Registers or updates an FCM device token for the authenticated user. "
              + "If the token already exists for another user it will be reassigned. "
              + "Call this after the user logs in or whenever the FCM token is refreshed.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Device registered",
        content = @Content(schema = @Schema(implementation = UserDeviceResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error — deviceToken is required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<UserDeviceResponse>> registerDevice(
      @Valid @RequestBody RegisterDeviceRequest request,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    log.info("Register device userId={} platform={}", userDetails.getUserId(), request.getPlatform());
    UserDevice device = userDeviceService.registerDevice(
        userDetails.getUserId(), request.getDeviceToken(), request.getPlatform());
    return ResponseEntity.ok(ApiResponse.success(toResponse(device)));
  }

  @Operation(
      summary = "Unregister device token",
      description =
          "Soft-deletes the specified FCM device token for the authenticated user. "
              + "Call this on logout to stop push notifications to the device.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "Device unregistered"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required")
  })
  @DeleteMapping("/{deviceToken}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> unregisterDevice(
      @Parameter(description = "FCM device token to remove") @PathVariable String deviceToken,
      @AuthenticationPrincipal FamsUserDetails userDetails) {
    log.info("Unregister device userId={} token={}", userDetails.getUserId(), MaskingUtils.maskToken(deviceToken));
    userDeviceService.unregisterDevice(userDetails.getUserId(), deviceToken);
    return ResponseEntity.noContent().build();
  }

  private UserDeviceResponse toResponse(UserDevice d) {
    return UserDeviceResponse.builder()
        .id(d.getId())
        .userId(d.getUserId())
        .deviceToken(d.getDeviceToken())
        .platform(d.getPlatform())
        .createdAt(d.getCreatedAt())
        .build();
  }
}
