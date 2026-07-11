package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "Paginated notification inbox response")
public class NotificationPageResponse {

  @Schema(description = "List of notifications on this page")
  private List<NotificationResponse> items;

  @Schema(description = "Zero-based page index", example = "0")
  private int page;

  @Schema(description = "Number of items per page", example = "20")
  private int size;

  @Schema(description = "Total number of notifications matching the query", example = "42")
  private long totalElements;

  @Schema(description = "Total number of pages", example = "3")
  private int totalPages;

  @Schema(description = "Whether this is the first page", example = "true")
  private boolean first;

  @Schema(description = "Whether this is the last page", example = "false")
  private boolean last;

  @Schema(
      description = "Total unread notification count for this user in the tenant (regardless of filter)",
      example = "5")
  private long unreadCount;
}
