package com.ticketing.ticketing;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/** Proves credential integrity only; the caller must separately check the current ticket and session. */
public class TicketCredentialCodec {
    private static final String VERSION = "TKT1";
    private static final Pattern TICKET_NUMBER = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ENCODED_MAC = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final TicketCredentialKeyRing keys;

    TicketCredentialCodec(TicketCredentialKeyRing keys) { this.keys = keys; }

    public String encode(String ticketNumber) {
        if (!canonicalTicketNumber(ticketNumber)) { throw invalid(); }
        String payload = VERSION + "." + keys.activeKid() + "." + ticketNumber;
        return payload + "." + ENCODER.encodeToString(mac(payload, keys.key(keys.activeKid())));
    }

    public String verify(String credential) {
        if (credential == null || credential.length() > 256) { throw invalid(); }
        var parts = credential.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !TicketCredentialKeyRing.KID.matcher(parts[1]).matches()
                || !canonicalTicketNumber(parts[2]) || !ENCODED_MAC.matcher(parts[3]).matches()) {
            throw invalid();
        }
        var key = keys.key(parts[1]);
        if (key == null) { throw invalid(); }
        byte[] supplied;
        try { supplied = Base64.getUrlDecoder().decode(parts[3]); }
        catch (IllegalArgumentException exception) { throw invalid(); }
        if (supplied.length != 32 || !ENCODER.encodeToString(supplied).equals(parts[3])) { throw invalid(); }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(mac(payload, key), supplied)) { throw invalid(); }
        return parts[2];
    }

    private static boolean canonicalTicketNumber(String number) {
        return number != null && TICKET_NUMBER.matcher(number).matches();
    }

    private static byte[] mac(String payload, SecretKey key) {
        try {
            // Mac is mutable, so each operation gets its own instance for concurrent queries.
            var algorithm = Mac.getInstance("HmacSHA256");
            algorithm.init(key);
            return algorithm.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Ticket credential cryptography is unavailable");
        }
    }

    private static BusinessException invalid() {
        return new BusinessException("INVALID_CREDENTIAL", "票码无效", HttpStatus.BAD_REQUEST);
    }
}
