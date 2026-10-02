package com.ticketing.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationStartupIT {
    @Autowired
    private TestDatabaseMapper mapper;
    @Autowired
    private Flyway flyway;
    @Value("${local.server.port}")
    private int port;

    @Test
    void myBatisAndFlywayUseTheDedicatedTestDatabase() {
        assertEquals("ticketing_test", mapper.currentDatabase());
        assertNotNull(flyway.info());
    }

    @Test
    void realHttpServerExposesHealthAndOpenApi() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/actuator/health", "/v3/api-docs", "/api/system/ping"}) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                if (path.equals("/actuator/health")) {
                    assertTrue(response.body().contains("\"UP\""));
                } else if (path.equals("/v3/api-docs")) {
                    assertTrue(response.body().contains("/api/system/ping"));
                }
            }
        }
    }
}
