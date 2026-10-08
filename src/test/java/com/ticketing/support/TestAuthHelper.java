package com.ticketing.support;

import com.ticketing.identity.DemoAccountSeeder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Test credentials only; all HTTP tests obtain tokens through the real login entry point. */
public final class TestAuthHelper {
    public static final String PASSWORD = "test-only-Passw0rd!";
    private TestAuthHelper() { }

    public static String seedAndLogin(DemoAccountSeeder seeder, JsonMapper json, int port, String loginName)
            throws Exception {
        seeder.seed(PASSWORD);
        return login(json, port, loginName);
    }

    public static String login(JsonMapper json, int port, String loginName) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"loginName":"%s","password":"%s"}
                            """.formatted(loginName, PASSWORD))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            String token = json.readTree(response.body()).path("data").path("token").asString();
            assertFalse(token.isBlank());
            return token;
        }
    }
}
