package com.fams.shared.util;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a String field for automatic PII masking during JSON serialization.
 * Masking is skipped for PLATFORM_ADMIN callers and TENANT_ADMIN callers
 * (identified by the users:create permission).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@JacksonAnnotationsInside
@JsonSerialize(using = MaskedSerializer.class)
public @interface Masked {
    MaskType type() default MaskType.DEFAULT;

    enum MaskType {
        EMAIL,
        PHONE,
        TOKEN,
        DEFAULT
    }
}
