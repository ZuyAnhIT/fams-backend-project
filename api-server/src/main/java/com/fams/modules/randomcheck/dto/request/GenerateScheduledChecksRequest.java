package com.fams.modules.randomcheck.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class GenerateScheduledChecksRequest {

    @Schema(description = "Date to generate checks for (defaults to today if omitted)", example = "2026-06-28")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;
}
