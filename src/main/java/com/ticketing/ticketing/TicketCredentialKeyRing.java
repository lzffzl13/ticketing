package com.ticketing.ticketing;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** An immutable startup snapshot: only the active key signs; all retained keys verify. */
final class TicketCredentialKeyRing {
    static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final String activeKid;
    private final Map<String, SecretKey> keys;

    TicketCredentialKeyRing(String activeKid, Map<String, byte[]> material) {
        if (activeKid == null || !KID.matcher(activeKid).matches() || material == null || material.isEmpty()) {
            throw invalidConfiguration();
        }
        var copied = new HashMap<String, SecretKey>();
        material.forEach((kid, bytes) -> {
            if (kid == null || !KID.matcher(kid).matches() || bytes == null || bytes.length != 32) {
                throw invalidConfiguration();
            }
            copied.put(kid, new SecretKeySpec(bytes, "HmacSHA256"));
        });
        if (!copied.containsKey(activeKid)) { throw invalidConfiguration(); }
        this.activeKid = activeKid;
        this.keys = Map.copyOf(copied);
    }

    String activeKid() { return activeKid; }
    SecretKey key(String kid) { return keys.get(kid); }

    static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Ticket credential key configuration is missing or invalid");
    }
}
