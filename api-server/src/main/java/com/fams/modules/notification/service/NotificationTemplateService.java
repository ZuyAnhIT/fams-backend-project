package com.fams.modules.notification.service;

import com.fams.modules.notification.dto.request.CreateTemplateRequest;
import com.fams.modules.notification.dto.request.UpdateTemplateRequest;
import com.fams.modules.notification.dto.response.NotificationTemplateResponse;
import com.fams.modules.notification.entity.NotificationTemplate;
import com.fams.modules.notification.repository.NotificationTemplateRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class NotificationTemplateService {

  private final NotificationTemplateRepository templateRepository;

  public NotificationTemplateService(NotificationTemplateRepository templateRepository) {
    this.templateRepository = templateRepository;
  }

  /**
   * Creates a new notification template for the given tenant.
   *
   * @param tenantId tenant UUID from path
   * @param req create request DTO
   * @return created template response
   * @throws DuplicateResourceException if a template with same tenant+event_type+locale already exists
   */
  @Transactional
  public NotificationTemplateResponse createTemplate(UUID tenantId, CreateTemplateRequest req) {
    String locale = req.getLocale() != null ? req.getLocale() : "vi";
    log.info("Creating notification template tenantId={} eventType={} locale={}", tenantId, req.getEventType(), locale);

    templateRepository.findByTenantIdAndEventTypeAndLocaleAndDeletedAtIsNull(
            tenantId, req.getEventType(), locale)
        .ifPresent(existing -> {
          throw new DuplicateResourceException(
              "Notification template already exists for eventType=" + req.getEventType()
                  + " locale=" + locale);
        });

    NotificationTemplate template = NotificationTemplate.builder()
        .tenantId(tenantId)
        .eventType(req.getEventType())
        .locale(locale)
        .titleTemplate(req.getTitleTemplate())
        .bodyTemplate(req.getBodyTemplate())
        .build();

    NotificationTemplate saved = templateRepository.save(template);
    log.debug("Notification template created id={}", saved.getId());
    return toResponse(saved);
  }

  /**
   * Updates an existing notification template.
   *
   * @param templateId template UUID
   * @param tenantId tenant UUID from path
   * @param req update request DTO (all fields optional)
   * @return updated template response
   * @throws ResourceNotFoundException if template not found
   * @throws DuplicateResourceException if the new event_type+locale combination already exists
   */
  @Transactional
  public NotificationTemplateResponse updateTemplate(UUID templateId, UUID tenantId, UpdateTemplateRequest req) {
    log.info("Updating notification template id={} tenantId={}", templateId, tenantId);

    NotificationTemplate template = templateRepository
        .findByIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)
        .orElseThrow(() -> new ResourceNotFoundException("Notification template not found: " + templateId));

    String newEventType = req.getEventType() != null ? req.getEventType() : template.getEventType();
    String newLocale = req.getLocale() != null ? req.getLocale() : template.getLocale();

    // Check for duplicate if eventType or locale changed
    boolean eventTypeChanged = !newEventType.equals(template.getEventType());
    boolean localeChanged = !newLocale.equals(template.getLocale());
    if (eventTypeChanged || localeChanged) {
      templateRepository.findByTenantIdAndEventTypeAndLocaleAndDeletedAtIsNull(tenantId, newEventType, newLocale)
          .filter(existing -> !existing.getId().equals(templateId))
          .ifPresent(existing -> {
            throw new DuplicateResourceException(
                "Notification template already exists for eventType=" + newEventType
                    + " locale=" + newLocale);
          });
    }

    if (req.getEventType() != null) {
      template.setEventType(req.getEventType());
    }
    if (req.getLocale() != null) {
      template.setLocale(req.getLocale());
    }
    if (req.getTitleTemplate() != null) {
      template.setTitleTemplate(req.getTitleTemplate());
    }
    if (req.getBodyTemplate() != null) {
      template.setBodyTemplate(req.getBodyTemplate());
    }

    NotificationTemplate saved = templateRepository.save(template);
    log.debug("Notification template updated id={}", saved.getId());
    return toResponse(saved);
  }

  /**
   * Soft-deletes a notification template.
   *
   * @param templateId template UUID
   * @param tenantId tenant UUID from path
   * @throws ResourceNotFoundException if template not found
   */
  @Transactional
  public void deleteTemplate(UUID templateId, UUID tenantId) {
    log.info("Deleting notification template id={} tenantId={}", templateId, tenantId);

    NotificationTemplate template = templateRepository
        .findByIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)
        .orElseThrow(() -> new ResourceNotFoundException("Notification template not found: " + templateId));

    template.setDeletedAt(OffsetDateTime.now());
    templateRepository.save(template);
    log.debug("Notification template soft-deleted id={}", templateId);
  }

  /**
   * Returns a paginated list of templates for a tenant.
   *
   * @param tenantId tenant UUID from path
   * @param pageable pagination parameters
   * @return page of template responses
   */
  @Transactional(readOnly = true)
  public Page<NotificationTemplateResponse> listTemplates(UUID tenantId, Pageable pageable) {
    log.debug("Listing notification templates tenantId={}", tenantId);
    return templateRepository
        .findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable)
        .map(this::toResponse);
  }

  /**
   * Returns a single template by id for a tenant.
   *
   * @param templateId template UUID
   * @param tenantId tenant UUID from path
   * @return template response
   * @throws ResourceNotFoundException if not found
   */
  @Transactional(readOnly = true)
  public NotificationTemplateResponse getTemplate(UUID templateId, UUID tenantId) {
    log.debug("Getting notification template id={} tenantId={}", templateId, tenantId);
    return templateRepository
        .findByIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)
        .map(this::toResponse)
        .orElseThrow(() -> new ResourceNotFoundException("Notification template not found: " + templateId));
  }

  /**
   * Renders a template by substituting {variable} placeholders with provided values.
   *
   * @param tenantId tenant UUID
   * @param eventType event type string
   * @param locale locale code
   * @param vars map of variable name to value for substitution
   * @return rendered title and body as a two-element array [title, body]
   * @throws ResourceNotFoundException if template not found for given tenant/eventType/locale
   */
  @Transactional(readOnly = true)
  public String[] renderTemplate(UUID tenantId, String eventType, String locale, Map<String, String> vars) {
    log.debug("Rendering template tenantId={} eventType={} locale={}", tenantId, eventType, locale);

    NotificationTemplate template = templateRepository
        .findByTenantIdAndEventTypeAndLocaleAndDeletedAtIsNull(tenantId, eventType, locale)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Notification template not found for eventType=" + eventType + " locale=" + locale));

    String title = substitute(template.getTitleTemplate(), vars);
    String body = substitute(template.getBodyTemplate(), vars);
    return new String[]{title, body};
  }

  private String substitute(String template, Map<String, String> vars) {
    if (vars == null || vars.isEmpty()) {
      return template;
    }
    String result = template;
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      result = result.replace("{" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }

  private NotificationTemplateResponse toResponse(NotificationTemplate t) {
    return NotificationTemplateResponse.builder()
        .id(t.getId())
        .tenantId(t.getTenantId())
        .eventType(t.getEventType())
        .locale(t.getLocale())
        .titleTemplate(t.getTitleTemplate())
        .bodyTemplate(t.getBodyTemplate())
        .createdAt(t.getCreatedAt())
        .updatedAt(t.getUpdatedAt())
        .build();
  }
}
