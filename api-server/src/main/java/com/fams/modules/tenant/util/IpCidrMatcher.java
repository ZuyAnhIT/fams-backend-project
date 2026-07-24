package com.fams.modules.tenant.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Matches a client IP against a whitelist entry that may be a bare address or CIDR range
 * (both IPv4 and IPv6) — see {@code TenantIpWhitelist.ipAddress}. No external dependency:
 * {@link InetAddress} already does the string parsing/normalization we need (e.g. treating
 * "::1" and "0:0:0:0:0:0:0:1" as equal, or expanding a compressed IPv6 form).
 */
public final class IpCidrMatcher {

    private IpCidrMatcher() {
    }

    public static boolean matches(String clientIp, String entry) {
        if (clientIp == null || entry == null) {
            return false;
        }
        try {
            int slash = entry.indexOf('/');
            if (slash < 0) {
                return InetAddress.getByName(entry).equals(InetAddress.getByName(clientIp));
            }

            String base = entry.substring(0, slash);
            int prefixLength = Integer.parseInt(entry.substring(slash + 1));
            byte[] baseBytes = InetAddress.getByName(base).getAddress();
            byte[] clientBytes = InetAddress.getByName(clientIp).getAddress();

            // IPv4 (4 bytes) vs IPv6 (16 bytes) — an entry can never match a client address
            // from the other family.
            if (baseBytes.length != clientBytes.length) {
                return false;
            }
            if (prefixLength < 0 || prefixLength > baseBytes.length * 8) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (baseBytes[i] != clientBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((baseBytes[fullBytes] & mask) != (clientBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            // Malformed input (shouldn't happen — CreateIpWhitelistRequest validates format,
            // and the client IP comes from the request itself) fails closed: no match.
            return false;
        }
    }
}
