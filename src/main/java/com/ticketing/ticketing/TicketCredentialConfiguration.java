package com.ticketing.ticketing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Properties;

@Configuration
public class TicketCredentialConfiguration {
    @Bean
    TicketCredentialKeyRing ticketCredentialKeyRing(
            @Value("${ticketing.ticket-credential.key-file:${TICKET_CREDENTIAL_KEY_FILE:}}") String location) {
        return loadKeyRing(location);
    }

    @Bean
    TicketCredentialCodec ticketCredentialCodec(TicketCredentialKeyRing keys) {
        return new TicketCredentialCodec(keys);
    }

    @Bean
    QrCodeRenderer qrCodeRenderer() { return new QrCodeRenderer(); }

    static TicketCredentialKeyRing loadKeyRing(String location) {
        if (location == null || location.isBlank()) { throw TicketCredentialKeyRing.invalidConfiguration(); }
        try (var input = open(location)) {
            // Bound malformed local configuration before parsing; no file content appears in failures.
            byte[] content = input.readNBytes(65537);
            if (content.length > 65536) { throw TicketCredentialKeyRing.invalidConfiguration(); }
            var properties = new UniqueProperties();
            properties.load(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
            var material = new HashMap<String, byte[]>();
            for (var name : properties.stringPropertyNames()) {
                if (name.equals("activeKid")) { continue; }
                if (!name.startsWith("key.") || !TicketCredentialKeyRing.KID.matcher(name.substring(4)).matches()) {
                    throw TicketCredentialKeyRing.invalidConfiguration();
                }
                String encoded = properties.getProperty(name);
                byte[] decoded = Base64.getDecoder().decode(encoded);
                if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                    throw TicketCredentialKeyRing.invalidConfiguration();
                }
                material.put(name.substring(4), decoded);
            }
            return new TicketCredentialKeyRing(properties.getProperty("activeKid"), material);
        } catch (IOException | IllegalArgumentException exception) {
            // Parser and path exceptions can contain secret source text; do not retain their causes.
            throw TicketCredentialKeyRing.invalidConfiguration();
        }
    }

    private static InputStream open(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length())).getInputStream();
        }
        Path path = location.startsWith("file:") ? Path.of(URI.create(location)) : Path.of(location);
        return Files.newInputStream(path);
    }

    private static final class UniqueProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) { throw TicketCredentialKeyRing.invalidConfiguration(); }
            return super.put(key, value);
        }
    }
}
