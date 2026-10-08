package com.ticketing.identity;

import com.ticketing.support.Week2Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ticketing.demo-password=")
@ActiveProfiles("test")
@Import(IdentityIT.TestClock.class)
class IdentityIT {
    private static final String PASSWORD = "identity-test-password";
    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00.123456Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private DemoAccountSeeder seeder;
    @Autowired private MutableClock clock;
    @Value("${local.server.port}") private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void prepare() {
        Week2Fixtures.resetTestDatabase(jdbc);
        clock.set(NOW);
        seeder.seed(PASSWORD);
    }

    @Test
    void loginReturnsPublicIdentityAndOnlyDigestIsStoredWithoutSessionCookie() throws Exception {
        var response = login("CONSUMER1", PASSWORD);
        var data = data(response, 200);
        String token = data.path("token").asString();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(NOW.plusSeconds(7200), Instant.parse(data.path("expiresAt").asString()));
        assertEquals("consumer1", data.path("account").path("loginName").asString());
        assertEquals("CONSUMER", data.path("account").path("role").asString());
        assertEquals(3, data.path("account").size());
        assertFalse(response.body().contains("password"));
        assertFalse(response.body().contains("digest"));
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(IdentityService.digest(token), jdbc.queryForObject("SELECT token_digest FROM demo_login_token", String.class));
        assertNotEquals(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM account WHERE login_name='consumer1'", String.class));
    }

    @Test
    void unknownWrongPasswordAndDisabledAccountHaveSameLoginFailureAndNoToken() throws Exception {
        for (var response : List.of(login("unknown", PASSWORD), login("consumer1", "wrong-password"))) {
            assertEquals("UNAUTHORIZED", body(response, 401).path("code").asString());
        }
        jdbc.update("UPDATE account SET enabled=FALSE WHERE login_name='consumer1'");
        assertEquals("UNAUTHORIZED", body(login("consumer1", PASSWORD), 401).path("code").asString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM demo_login_token", Integer.class));
    }

    @Test
    void protectedWritesRequireBearerHeaderAndReturnRequestIds() throws Exception {
        String token = token("operator1");
        assertEquals("UNAUTHORIZED", body(request("POST", "/api/venues", "{}", Map.of()), 401).path("code").asString());
        assertEquals("UNAUTHORIZED", body(request("POST", "/api/venues", "{}",
                Map.of("Authorization", "Bearer invalid")), 401).path("code").asString());
        assertEquals(401, request("POST", "/api/venues", "{}", Map.of("Authorization", "Basic " + token)).statusCode());
        assertEquals(401, request("POST", "/api/venues?token=" + token, "{}", Map.of()).statusCode());
        assertEquals(401, request("POST", "/api/venues", "{}", Map.of("Cookie", "JSESSIONID=" + token)).statusCode());
    }

    @Test
    void consumersAndCheckersCannotManageButOperatorCanWrite() throws Exception {
        String payload = json.writeValueAsString(Map.of("code", "IDENTITY-VENUE", "name", "身份测试厅", "enabled", true));
        for (String loginName : List.of("consumer1", "checker1")) {
            assertEquals("FORBIDDEN", body(request("POST", "/api/venues", payload,
                    bearer(token(loginName))), 403).path("code").asString());
        }
        assertEquals("IDENTITY-VENUE", data(request("POST", "/api/venues", payload,
                bearer(token("operator1"))), 201).path("code").asString());
    }

    @Test
    void consumerCanReachPersonalOrderRouteAndOtherRolesCannot() throws Exception {
        assertEquals(404, request("GET", "/api/orders/9223372036854775807", null, bearer(token("consumer1"))).statusCode());
        assertEquals(401, request("GET", "/api/orders/9223372036854775807", null, Map.of()).statusCode());
        for (String loginName : List.of("operator1", "checker1")) {
            assertEquals(403, request("GET", "/api/orders/9223372036854775807", null, bearer(token(loginName))).statusCode());
        }
    }

    @Test
    void expiredTokenIsRejectedAtExactDeadlineAndBeforeDeadlineItAuthenticates() throws Exception {
        var login = data(login("consumer1", PASSWORD), 200);
        var bearer = bearer(login.path("token").asString());
        var expiresAt = Instant.parse(login.path("expiresAt").asString());
        clock.set(expiresAt.minusNanos(1000));
        assertEquals(404, request("GET", "/api/orders/9223372036854775807", null, bearer).statusCode());
        clock.set(expiresAt);
        assertEquals("UNAUTHORIZED", body(request("GET", "/api/orders/9223372036854775807", null, bearer), 401).path("code").asString());
        clock.set(expiresAt.plusNanos(1000));
        assertEquals(401, request("GET", "/api/orders/9223372036854775807", null, bearer).statusCode());
    }

    @Test
    void disablingAccountAndRemovingTokenAreEffectiveOnNextRequest() throws Exception {
        String consumer = token("consumer1");
        String operator = token("operator1");
        jdbc.update("UPDATE account SET enabled=FALSE WHERE login_name='consumer1'");
        assertEquals(401, request("GET", "/api/orders/9223372036854775807", null, bearer(consumer)).statusCode());
        jdbc.update("DELETE FROM demo_login_token WHERE token_digest=?", IdentityService.digest(operator));
        assertEquals(401, request("POST", "/api/venues", "{}", bearer(operator)).statusCode());
    }

    @Test
    void repeatedLoginKeepsBothIndependentTokensUsable() throws Exception {
        String first = token("consumer1");
        String second = token("consumer1");
        assertNotEquals(first, second);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM demo_login_token", Integer.class));
        assertEquals(404, request("GET", "/api/orders/9223372036854775807", null, bearer(first)).statusCode());
        assertEquals(404, request("GET", "/api/orders/9223372036854775807", null, bearer(second)).statusCode());
    }

    @Test
    void publicReadsRemainAnonymousAndUnknownRoutesAreDenied() throws Exception {
        assertEquals(200, request("GET", "/api/system/ping", null, Map.of()).statusCode());
        assertEquals(200, request("GET", "/actuator/health", null, Map.of()).statusCode());
        assertEquals(200, request("GET", "/v3/api-docs", null, Map.of()).statusCode());
        assertEquals(404, request("GET", "/api/venues/9223372036854775807", null, Map.of()).statusCode());
        assertEquals(401, request("GET", "/api/not-a-route", null, Map.of()).statusCode());
        assertEquals(403, request("GET", "/api/not-a-route", null, bearer(token("operator1"))).statusCode());
    }

    @Test
    void repeatSeedPreservesAccountsAndDifferencesAbortWithoutOverwriting() {
        var before = jdbc.queryForList("SELECT * FROM account ORDER BY login_name");
        clock.set(NOW.plusSeconds(60));
        seeder.seed(PASSWORD);
        assertEquals(before, jdbc.queryForList("SELECT * FROM account ORDER BY login_name"));
        assertThrows(IllegalStateException.class, () -> seeder.seed("different-demo-password"));
        assertEquals(before, jdbc.queryForList("SELECT * FROM account ORDER BY login_name"));
        jdbc.update("UPDATE account SET role='CHECKER' WHERE login_name='operator1'");
        var changed = jdbc.queryForList("SELECT * FROM account ORDER BY login_name");
        assertThrows(IllegalStateException.class, () -> seeder.seed(PASSWORD));
        assertEquals(changed, jdbc.queryForList("SELECT * FROM account ORDER BY login_name"));
    }

    @Test
    void mismatchAfterCreatingMissingAccountsRollsBackWholeSeed() {
        jdbc.update("DELETE FROM account WHERE login_name IN ('consumer1', 'consumer2')");
        jdbc.update("UPDATE account SET role='CHECKER' WHERE login_name='operator1'");
        var before = jdbc.queryForList("SELECT * FROM account ORDER BY login_name");
        assertThrows(IllegalStateException.class, () -> seeder.seed(PASSWORD));
        assertEquals(before, jdbc.queryForList("SELECT * FROM account ORDER BY login_name"));
    }

    @Test
    void malformedLoginIsRejectedWithoutCreatingToken() throws Exception {
        assertEquals(400, login("", PASSWORD).statusCode());
        assertEquals(400, login("consumer1", "").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM demo_login_token", Integer.class));
    }

    private HttpResponse<String> login(String loginName, String password) throws Exception {
        return request("POST", "/api/auth/login", json.writeValueAsString(Map.of("loginName", loginName, "password", password)), Map.of());
    }

    private String token(String loginName) throws Exception { return data(login(loginName, PASSWORD), 200).path("token").asString(); }
    private Map<String, String> bearer(String token) { return Map.of("Authorization", "Bearer " + token); }

    private HttpResponse<String> request(String method, String path, String payload, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(builder::header);
        if (payload != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), body.path("requestId").asString());
        return body;
    }

    private JsonNode data(HttpResponse<String> response, int status) throws Exception { return body(response, status).path("data"); }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock identityTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = NOW;
        void set(Instant at) { instant = at; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) { throw new IllegalArgumentException("Test clock uses UTC"); }
            return this;
        }
        @Override public Instant instant() { return instant; }
    }
}
