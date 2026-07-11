package com.fams.modules.notification.controller;

import com.fams.modules.notification.dto.request.CreateTemplateRequest;
import com.fams.modules.notification.dto.request.UpdateTemplateRequest;
import com.fams.modules.notification.dto.response.NotificationTemplateResponse;
import com.fams.modules.notification.service.NotificationTemplateService;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Notification Templates", description = "Admin CRUD for notification content templates")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/notification-templates")
public class NotificationTemplateController {

  private final NotificationTemplateService templateService;

  public NotificationTemplateController(NotificationTemplateService templateService) {
    this.templateService = templateService;
  }

  @Operation(
      summary = "Create a notification template",
      description =
          "Creates a new notification template for the given tenant. "
              + "The combination of tenant_id + event_type + locale must be unique.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Template created",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — requires notifications:manage or tenant:admin authority"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Duplicate — template with same eventType+locale already exists")
  })
  @PostMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('notifications:manage', 'tenant:admin')")
  public ResponseEntity<ApiResponse<NotificationTemplateResponse>> createTemplate(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Valid @RequestBody CreateTemplateRequest request) {
    log.info("Create notification template tenantId={} eventType={}", tenantId, request.getEventType());
    NotificationTemplateResponse response = templateService.createTemplate(tenantId, request);
    return ResponseEntity.status(201).body(ApiResponse.success(response));
  }

  @Operation(
      summary = "List notification templates",
      description = "Returns a paginated list of notification templates for the given tenant.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Paginated list returned"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — requires notifications:manage or tenant:admin authority")
  })
  @GetMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('notifications:manage', 'tenant:admin')")
  public ResponseEntity<ApiResponse<Page<NotificationTemplateResponse>>> listTemplates(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Zero-based page index (default 0)")
          @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "Page size (default 20, max 100)")
          @RequestParam(defaultValue = "20") int size) {
    size = Math.min(size, 100);
    log.info("List notification templates tenantId={} page={} size={}", tenantId, page, size);
    Page<NotificationTemplateResponse> result = templateService.listTemplates(tenantId, PageRequest.of(page, size));
    return ResponseEntity.ok(ApiResponse.success(result));
  }

  @Operation(
      summary = "Get a notification template",
      description = "Returns a single notification template by ID for the given tenant.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Template returned",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — requires notifications:manage or tenant:admin authority"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Template not found")
  })
  @GetMapping("/{templateId}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('notifications:manage', 'tenant:admin')")
  public ResponseEntity<ApiResponse<NotificationTemplateResponse>> getTemplate(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Template UUID") @PathVariable UUID templateId) {
    log.info("Get notification template id={} tenantId={}", templateId, tenantId);
    NotificationTemplateResponse response = templateService.getTemplate(templateId, tenantId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @Operation(
      summary = "Update a notification template",
      description =
          "Updates a notification template. All fields are optional — only provided fields are updated. "
              + "Updating eventType or locale to a combination that already exists returns 409.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Template updated",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — requires notifications:manage or tenant:admin authority"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Template not found"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Duplicate — target eventType+locale combination already exists")
  })
  @PutMapping("/{templateId}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('notifications:manage', 'tenant:admin')")
  public ResponseEntity<ApiResponse<NotificationTemplateResponse>> updateTemplate(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Template UUID") @PathVariable UUID templateId,
      @RequestBody UpdateTemplateRequest request) {
    log.info("Update notification template id={} tenantId={}", templateId, tenantId);
    NotificationTemplateResponse response = templateService.updateTemplate(templateId, tenantId, request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @Operation(
      summary = "Delete a notification template",
      description = "Soft-deletes a notification template. Returns 204 on success.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "Template deleted"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — requires notifications:manage or tenant:admin authority"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Template not found")
  })
  @DeleteMapping("/{templateId}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('notifications:manage', 'tenant:admin')")
  public ResponseEntity<Void> deleteTemplate(
      @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
      @Parameter(description = "Template UUID") @PathVariable UUID templateId) {
    log.info("Delete notification template id={} tenantId={}", templateId, tenantId);
    templateService.deleteTemplate(templateId, tenantId);
    return ResponseEntity.noContent().build();
  }
}
