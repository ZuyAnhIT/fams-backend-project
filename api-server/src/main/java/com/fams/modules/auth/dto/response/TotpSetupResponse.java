package com.fams.modules.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TotpSetupResponse {
    private String setupToken;
    private String qrCodeUrl;
    private String manualEntryKey;
}
