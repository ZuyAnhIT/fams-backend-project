package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response for bulk mark-as-read operation")
public class MarkAllReadResponse {

  @Schema(description = "Number of notifications that were marked as read", example = "5")
  private int markedCount;
}
