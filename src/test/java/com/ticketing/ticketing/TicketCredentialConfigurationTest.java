package com.ticketing.ticketing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TicketCredentialConfigurationTest {
    private static final String K1 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
    private static final String K2 = "ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A=";
    private static final String NUMBER = "3eefbb49-3640-4758-a7d8-5509c11fbbb1";
    @TempDir Path directory;

    @Test
    void propertiesLoadFromPlainFilePathAndFileUri() throws Exception {
        Path file = write("activeKid=K1\nkey.K1=" + K1 + "\n");
        var plain = codec(file.toString());
        var uri = codec(file.toUri().toString());
        assertEquals(plain.encode(NUMBER), uri.encode(NUMBER));
        assertEquals(NUMBER, uri.verify(plain.encode(NUMBER)));
    }

    @Test
    void classpathConfigurationUsesTheSameStrictLoader() {
        var loaded = codec("classpath:ticket-credential-loader-test.properties");
        assertTrue(loaded.encode(NUMBER).startsWith("TKT1.loader-test."));
        assertEquals(NUMBER, loaded.verify(loaded.encode(NUMBER)));
    }

    @Test
    void missingBlankUnreadableOrOversizedConfigurationFailsWithoutFallback() throws Exception {
        for (String location : new String[] { null, "", " ", directory.resolve("absent.properties").toString(),
                "classpath:absent-ticket-credential-keys.properties", directory.toString() }) {
            assertSafeFailure(() -> TicketCredentialConfiguration.loadKeyRing(location));
        }
        Path oversized = write("#" + "x".repeat(65536));
        assertSafeFailure(() -> TicketCredentialConfiguration.loadKeyRing(oversized.toString()));
    }

    @Test
    void missingActiveUnknownActiveMissingKeysUnknownFieldsAndInvalidIdentifiersAreRejected() throws Exception {
        for (String content : List.of("", "key.K1=" + K1, "activeKid=K1", "activeKid=K2\nkey.K1=" + K1,
                "activeKid=K1\nkey.K1=" + K1 + "\nextra=secret-text",
                "activeKid=bad.kid\nkey.bad.kid=" + K1,
                "activeKid=K1\nkey.K1=" + K1 + "\nkey.=secret-text",
                "activeKid=" + "K".repeat(33) + "\nkey." + "K".repeat(33) + "=" + K1)) {
            Path file = write(content);
            assertSafeFailure(() -> TicketCredentialConfiguration.loadKeyRing(file.toString()));
        }
    }

    @Test
    void duplicateActiveAndDuplicateKeyAreRejectedEvenWhenTheRepeatedValuesAreEqual() throws Exception {
        for (String content : List.of("activeKid=K1\nactiveKid=K1\nkey.K1=" + K1,
                "activeKid=K1\nkey.K1=" + K1 + "\nkey.K1=" + K1,
                "activeKid=K1\nkey.K1=" + K1 + "\nkey.K\\u0031=" + K2)) {
            Path file = write(content);
            assertSafeFailure(() -> TicketCredentialConfiguration.loadKeyRing(file.toString()));
        }
    }

    @Test
    void keysRequireCanonicalStandardBase64AndExactlyThirtyTwoBytes() throws Exception {
        for (String key : List.of("secret-text", "AA==", "A".repeat(44), K1.substring(0, 43),
                K2.replace('+', '-'), K1.substring(0, 42) + "B=", K1 + " ", K1 + "=")) {
            Path file = write("activeKid=K1\nkey.K1=" + key);
            assertSafeFailure(() -> TicketCredentialConfiguration.loadKeyRing(file.toString()));
        }
    }

    @Test
    void validConfigurationIsASnapshotAndOnlyReloadingAppliesRotationOrRevocation() throws Exception {
        Path file = write("activeKid=K1\nkey.K1=" + K1);
        var running = codec(file.toString());
        String oldCredential = running.encode(NUMBER);
        Files.writeString(file, "activeKid=K2\nkey.K1=" + K1 + "\nkey.K2=" + K2);
        assertEquals(oldCredential, running.encode(NUMBER));
        var rotated = codec(file.toString());
        assertEquals(NUMBER, rotated.verify(oldCredential));
        assertTrue(rotated.encode(NUMBER).startsWith("TKT1.K2."));
        Files.writeString(file, "activeKid=K2\nkey.K2=" + K2);
        var revoked = codec(file.toString());
        assertThrows(com.ticketing.common.web.BusinessException.class, () -> revoked.verify(oldCredential));
        assertEquals(NUMBER, rotated.verify(oldCredential)); // Running instances must actually be stopped/restarted.
    }

    @Test
    void explicitPropertyWiresTheCodecAndRendererAtStartup() throws Exception {
        Path file = write("activeKid=K1\nkey.K1=" + K1);
        new ApplicationContextRunner().withUserConfiguration(TicketCredentialConfiguration.class)
                .withPropertyValues("ticketing.ticket-credential.key-file=" + file)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(NUMBER, context.getBean(TicketCredentialCodec.class)
                            .verify(context.getBean(TicketCredentialCodec.class).encode(NUMBER)));
                    assertNotNull(context.getBean(QrCodeRenderer.class));
                });
    }

    @Test
    void environmentStylePropertyIsSupportedWithoutAConfiguredDefault() throws Exception {
        Path file = write("activeKid=K1\nkey.K1=" + K1);
        new ApplicationContextRunner().withUserConfiguration(TicketCredentialConfiguration.class)
                .withPropertyValues("TICKET_CREDENTIAL_KEY_FILE=" + file)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    private Path write(String content) throws IOException {
        Path file = directory.resolve("credential-keys.properties");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static TicketCredentialCodec codec(String location) {
        return new TicketCredentialCodec(TicketCredentialConfiguration.loadKeyRing(location));
    }

    private static void assertSafeFailure(Runnable action) {
        var error = assertThrows(IllegalStateException.class, action::run);
        assertEquals("Ticket credential key configuration is missing or invalid", error.getMessage());
        assertNull(error.getCause());
    }
}
