package com.fams.modules.auth.util;

/**
 * Single source of truth for phone normalization — every place that reads or writes
 * {@code users.phone} (register, login, profile phone change) must agree on the same
 * E.164-ish representation, or a number entered as "0912..." at one call site becomes
 * unreachable by "+84912..." at another.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    public static String normalize(String phone) {
        if (phone == null) return null;
        String cleaned = phone.trim();
        if (cleaned.startsWith("0")) {
            cleaned = "+84" + cleaned.substring(1);
        } else if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    public static String mask(String phone) {
        if (phone == null) return null;
        if (phone.length() <= 4) return "****";
        return phone.substring(0, phone.length() - 4) + "****";
    }
}
