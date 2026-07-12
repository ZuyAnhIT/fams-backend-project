package com.fams.shared.util;

import com.fams.shared.security.FamsUserDetails;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

public class MaskedSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final Masked.MaskType maskType;

    public MaskedSerializer() {
        this.maskType = Masked.MaskType.DEFAULT;
    }

    private MaskedSerializer(Masked.MaskType maskType) {
        this.maskType = maskType;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (isAdminCaller()) {
            gen.writeString(value);
            return;
        }
        gen.writeString(applyMask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        if (property != null) {
            Masked masked = property.getAnnotation(Masked.class);
            if (masked != null) {
                return new MaskedSerializer(masked.type());
            }
        }
        return this;
    }

    private boolean isAdminCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof FamsUserDetails user)) return false;
        if (user.isPlatformAdmin()) return true;
        // users:create is granted only to TENANT_ADMIN (company admin equivalent)
        return user.getAuthorities().stream()
                .anyMatch(a -> "users:create".equals(a.getAuthority()));
    }

    private String applyMask(String value) {
        return switch (maskType) {
            case EMAIL -> MaskingUtils.maskEmail(value);
            case PHONE -> MaskingUtils.maskPhone(value);
            case TOKEN -> MaskingUtils.maskToken(value);
            default -> "***";
        };
    }
}
