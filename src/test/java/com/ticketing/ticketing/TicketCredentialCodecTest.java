package com.ticketing.ticketing;

import com.ticketing.common.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TicketCredentialCodecTest {
    private static final String NUMBER = "3eefbb49-3640-4758-a7d8-5509c11fbbb1";
    private static final String OTHER = "f2e84987-604d-4474-9dae-2d5e85218867";
    private static final byte[] K1 = bytes(1);
    private static final byte[] K2 = bytes(33);
    private static final String GOLDEN = "TKT1.K1." + NUMBER + ".oO8DtOtUsNT8EGzyZgdhfDCR839fEUqOjRfMyx0E2ic";
    private final TicketCredentialCodec codec = codec("K1", Map.of("K1", K1));

    @Test
    void encodingMatchesAnIndependentDotNetHmacVectorAndUsesTheWholeMac() {
        // Expected value independently generated with .NET HMACSHA256 over the exact ASCII payload.
        assertEquals(GOLDEN, codec.encode(NUMBER));
        assertEquals(NUMBER, codec.verify(GOLDEN));
        assertEquals(43, GOLDEN.split("\\.")[3].length());
    }

    @Test
    void repeatedAndConcurrentQueriesProduceTheSameOriginalTicketCredential() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = IntStream.range(0, 64).mapToObj(i -> executor.submit(() -> {
                String credential = codec.encode(NUMBER);
                assertEquals(NUMBER, codec.verify(credential));
                return credential;
            })).toList();
            for (var call : calls) { assertEquals(GOLDEN, call.get()); }
        }
    }

    @Test
    void aRestartWithTheSameKeyMaterialPreservesTheCredential() {
        var restarted = codec("K1", Map.of("K1", K1.clone()));
        assertEquals(GOLDEN, restarted.encode(NUMBER));
        assertEquals(NUMBER, restarted.verify(GOLDEN));
    }

    @Test
    void normalRotationSignsWithK2AndBothRetainedKeysResolveToTheOriginalTicket() {
        var rotated = codec("K2", Map.of("K1", K1, "K2", K2));
        String newCredential = rotated.encode(NUMBER);
        assertTrue(newCredential.startsWith("TKT1.K2."));
        assertNotEquals(GOLDEN, newCredential);
        assertEquals(NUMBER, rotated.verify(GOLDEN));
        assertEquals(NUMBER, rotated.verify(newCredential));
        assertInvalid(codec, newCredential);
    }

    @Test
    void removingK1RevokesItsOldCodesWithoutAnyFallback() {
        var revoked = codec("K2", Map.of("K2", K2));
        assertInvalid(revoked, GOLDEN);
        assertEquals(NUMBER, revoked.verify(revoked.encode(NUMBER)));
    }

    @Test
    void changingTheTicketKeyIdentifierOrMacCannotRedirectAnExistingCredential() {
        var twoKeys = codec("K1", Map.of("K1", K1, "K2", K2));
        assertInvalid(twoKeys, GOLDEN.replace(NUMBER, OTHER));
        assertInvalid(twoKeys, GOLDEN.replace(".K1.", ".K2."));
        assertInvalid(twoKeys, GOLDEN.substring(0, GOLDEN.length() - 1) + "A");
        assertInvalid(codec("K1", Map.of("K1", K2)), GOLDEN);
    }

    @Test
    void formatVersionSegmentCountWhitespaceLengthAndNonAsciiInputAreStrict() {
        var invalid = Arrays.asList(null, "", NUMBER, "A".repeat(257), " TKT1.K1." + NUMBER,
                GOLDEN + " ", " " + GOLDEN, GOLDEN + ".", GOLDEN.replace("TKT1", "TKT2"),
                GOLDEN.replace("TKT1", "tkt1"), GOLDEN.replace(".K1.", ".."),
                GOLDEN.replace(".K1.", ".密钥."), GOLDEN.replace(".K1.", "." + "K".repeat(33) + "."),
                GOLDEN.substring(0, GOLDEN.lastIndexOf('.')), GOLDEN.replace(".K1.", ".https://key.example/.")
        );
        for (String value : invalid) { assertInvalid(codec, value); }
    }

    @Test
    void ticketNumbersMustUseFullLowercaseCanonicalUuidShape() {
        for (String value : Arrays.asList(null, "", NUMBER.toUpperCase(), NUMBER.replace("-", ""),
                "3eefbb49-3640-4758-a7d8-5509c11fbbb", "  " + NUMBER)) {
            var error = assertThrows(BusinessException.class, () -> codec.encode(value));
            assertEquals("INVALID_CREDENTIAL", error.getCode());
            if (value != null) { assertInvalid(codec, GOLDEN.replace(NUMBER, value)); }
        }
    }

    @Test
    void base64UrlMustBeUnpaddedCanonicalAndContainExactlyTheFullMac() {
        String prefix = GOLDEN.substring(0, GOLDEN.lastIndexOf('.') + 1);
        String originalMac = GOLDEN.substring(GOLDEN.lastIndexOf('.') + 1);
        for (String value : List.of(originalMac + "=", originalMac.substring(1), originalMac + "A",
                originalMac.replace('o', '+'), originalMac.substring(0, 42) + "/",
                originalMac.substring(0, 42) + "d")) {
            // Final d decodes to the same bytes as c because of unused bits, but is not canonical.
            assertInvalid(codec, prefix + value);
        }
    }

    @Test
    void keyMaterialIsCopiedInsteadOfRetainingAMutableCallerArray() {
        byte[] ownedByCaller = K1.clone();
        var copied = codec("K1", Map.of("K1", ownedByCaller));
        Arrays.fill(ownedByCaller, (byte) 0);
        assertEquals(GOLDEN, copied.encode(NUMBER));
    }

    private static TicketCredentialCodec codec(String active, Map<String, byte[]> material) {
        return new TicketCredentialCodec(new TicketCredentialKeyRing(active, material));
    }

    private static byte[] bytes(int first) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) { bytes[i] = (byte) (first + i); }
        return bytes;
    }

    private static void assertInvalid(TicketCredentialCodec target, String value) {
        var error = assertThrows(BusinessException.class, () -> target.verify(value));
        assertEquals("INVALID_CREDENTIAL", error.getCode());
        assertEquals(400, error.getStatus().value());
        assertEquals("票码无效", error.getMessage());
        assertNull(error.getCause());
    }
}
