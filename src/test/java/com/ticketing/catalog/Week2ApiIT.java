package com.ticketing.catalog;

import com.ticketing.support.Week2Fixtures;
import com.ticketing.support.Week2Fixtures.Fixture;
import com.ticketing.venue.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Week2ApiIT {
    @Autowired private VenueService venues;
    @Autowired private EventService events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Value("${local.server.port}") private int port;
    private Fixture fixture;

    @BeforeEach
    void prepare() {
        Week2Fixtures.resetTestDatabase(jdbc);
        fixture = Week2Fixtures.create(venues, events);
    }

    @Test
    void realHttpCreatesDraftWithOffsetTimesAndExposesOnlySnapshots() throws Exception {
        var response = send("POST", "/api/sessions", sessionBody("SESSION-HTTP"));
        assertEquals(201, response.statusCode(), response.body());
        var tree = json.readTree(response.body());
        assertEquals(56, tree.path("data").path("seatCount").asInt());
        assertEquals(54, tree.path("data").path("availableSeatCount").asInt());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), tree.path("requestId").asString());
        long id = tree.path("data").path("id").asLong();
        var session = data(send("GET", "/api/sessions/" + id, null), 200);
        assertEquals("2027-01-15T12:00:00Z", session.path("startsAt").asString());
        assertEquals("DRAFT", session.path("salesStatus").asString());
        assertEquals(56, data(send("GET", "/api/sessions/" + id + "/seats", null), 200).size());
        assertEquals(2, data(send("GET", "/api/sessions/" + id + "/price-tiers", null), 200).size());
        assertEquals(id, data(send("GET", "/api/sessions/by-code/SESSION-HTTP", null), 200).path("id").asLong());
        var docs = send("GET", "/v3/api-docs", null);
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("/api/sessions/{id}/seats"));
    }

    @Test
    void httpTemplateUpdateAndDeleteKeepOldSnapshotAndChangeNewSnapshot() throws Exception {
        long id = data(send("POST", "/api/sessions", sessionBody("SESSION-HTTP-OLD")), 201).path("id").asLong();
        var before = data(send("GET", "/api/sessions/" + id + "/seats", null), 200);
        var source = fixture.seat("FLOOR-A", "A", 1);
        var body = """
                {"areaId":%d,"rowLabel":"A","rowOrder":0,"seatNumber":1,
                 "coordinateX":99.125,"coordinateY":88.5,"aisleSegment":"NEW","enabled":false}
                """.formatted(fixture.floor().id());
        assertEquals(200, send("PUT", "/api/seat-templates/" + source.id(), body).statusCode());
        assertEquals(before, data(send("GET", "/api/sessions/" + id + "/seats", null), 200));
        assertEquals(200, send("DELETE", "/api/seat-templates/" + source.id(), null).statusCode());
        assertEquals(before, data(send("GET", "/api/sessions/" + id + "/seats", null), 200));
        assertEquals(55, data(send("POST", "/api/sessions", sessionBody("SESSION-HTTP-NEW")), 201).path("seatCount").asInt());
    }

    @Test
    void duplicateCodesHaveExplicit409Responses() throws Exception {
        var body = sessionBody("SESSION-HTTP-DUPLICATE");
        assertEquals(201, send("POST", "/api/sessions", body).statusCode());
        var duplicate = send("POST", "/api/sessions", body);
        assertEquals(409, duplicate.statusCode());
        assertEquals("SESSION_CODE_EXISTS", json.readTree(duplicate.body()).path("code").asString());
        var venue = send("POST", "/api/venues", """
                {"code":"VENUE-RULES","name":"重复场馆","enabled":true}
                """);
        assertEquals(409, venue.statusCode());
        assertEquals("DUPLICATE_RESOURCE", json.readTree(venue.body()).path("code").asString());
    }

    @Test
    void nestedPriceValidationAndMissingResourcesReturn4xxWithoutWrites() throws Exception {
        var invalid = send("POST", "/api/sessions", sessionBody("SESSION-INVALID").replace("380.00", "-1.00"));
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals("INVALID_REQUEST", json.readTree(invalid.body()).path("code").asString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session", Integer.class));
        assertEquals(404, send("GET", "/api/sessions/9223372036854775807/seats", null).statusCode());
        assertEquals(400, send("GET", "/api/sessions/-1", null).statusCode());
    }

    @Test
    void batchRequestValidatesEachSeatAndRollsBackDuplicatePositions() throws Exception {
        String path = "/api/areas/" + fixture.floor().id() + "/seat-templates";
        var invalid = send("POST", path, """
                [{"rowLabel":"Z","rowOrder":99,"seatNumber":0,"coordinateX":0,
                  "coordinateY":0,"aisleSegment":"L","enabled":true}]
                """);
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals(400, send("POST", path, "[]").statusCode());
        String seat = """
                {"rowLabel":"Z","rowOrder":99,"seatNumber":1,"coordinateX":0,
                 "coordinateY":0,"aisleSegment":"L","enabled":true}
                """;
        assertEquals(409, send("POST", path, "[" + seat + "," + seat + "]").statusCode());
        assertEquals(56, jdbc.queryForObject("SELECT COUNT(*) FROM seat_template", Integer.class));
    }

    @Test
    void httpConfigurationSalesAndTierSwitchesCompleteOneManagementFlow() throws Exception {
        long id = data(send("POST", "/api/sessions", sessionBody("SESSION-HTTP-MANAGE")), 201).path("id").asLong();
        long tierId = data(send("GET", "/api/sessions/" + id + "/price-tiers", null), 200).get(0).path("id").asLong();
        var configuration = configurationBody(tierId, "420.00");
        var configured = data(send("PUT", "/api/sessions/" + id + "/configuration", configuration), 200);
        assertEquals("2027-01-15T13:00:00Z", configured.path("startsAt").asString());
        var seats = data(send("GET", "/api/sessions/" + id + "/seats", null), 200);
        assertEquals(420, seats.get(0).path("priceSnapshot").asInt());
        var opened = data(send("PUT", "/api/sessions/" + id + "/sales-status", "{\"salesStatus\":\"ON_SALE\"}"), 200);
        var firstOpened = opened.path("firstOpenedAt").asString();
        assertFalse(firstOpened.isBlank());
        var disabled = data(send("PUT", "/api/sessions/" + id + "/price-tiers/" + tierId + "/enabled", "{\"enabled\":false}"), 200);
        assertFalse(disabled.path("enabled").asBoolean());
        assertEquals(seats, data(send("GET", "/api/sessions/" + id + "/seats", null), 200));
        data(send("PUT", "/api/sessions/" + id + "/sales-status", "{\"salesStatus\":\"STOPPED\"}"), 200);
        var frozen = send("PUT", "/api/sessions/" + id + "/configuration", configuration);
        assertEquals(409, frozen.statusCode());
        assertEquals("SESSION_CONFIGURATION_FROZEN", json.readTree(frozen.body()).path("code").asString());
        var resumed = data(send("PUT", "/api/sessions/" + id + "/sales-status", "{\"salesStatus\":\"ON_SALE\"}"), 200);
        assertEquals(firstOpened, resumed.path("firstOpenedAt").asString());
        var docs = send("GET", "/v3/api-docs", null);
        assertTrue(docs.body().contains("/api/sessions/{id}/configuration"));
        assertTrue(docs.body().contains("/api/sessions/{id}/sales-status"));
        assertTrue(docs.body().contains("/api/sessions/{id}/price-tiers/{tierId}/enabled"));
    }

    @Test
    void httpManagementRejectsInvalidNestedRequestsAndUnknownResources() throws Exception {
        long id = data(send("POST", "/api/sessions", sessionBody("SESSION-HTTP-MANAGE-INVALID")), 201).path("id").asLong();
        var path = "/api/sessions/" + id;
        long tierId = data(send("GET", path + "/price-tiers", null), 200).get(0).path("id").asLong();
        assertEquals(400, send("PUT", path + "/configuration", configurationBody(tierId, "-1.00")).statusCode());
        assertEquals(400, send("PUT", path + "/configuration", configurationBody(tierId, "420.00")
                .replace("\"seats\":[]", "\"seats\":[null]")).statusCode());
        assertEquals(400, send("PUT", path + "/sales-status", "{\"salesStatus\":\"UNKNOWN\"}").statusCode());
        assertEquals(400, send("PUT", path + "/sales-status", "{}").statusCode());
        assertEquals(400, send("PUT", path + "/price-tiers/" + tierId + "/enabled", "{}").statusCode());
        assertEquals(404, send("PUT", "/api/sessions/9223372036854775807/sales-status", "{\"salesStatus\":\"ON_SALE\"}").statusCode());
        assertEquals(404, send("PUT", path + "/price-tiers/9223372036854775807/enabled", "{\"enabled\":false}").statusCode());
        assertEquals(409, send("PUT", path + "/sales-status", "{\"salesStatus\":\"STOPPED\"}").statusCode());
        var session = data(send("GET", path, null), 200);
        assertEquals("DRAFT", session.path("salesStatus").asString());
        assertEquals("2027-01-15T12:00:00Z", session.path("startsAt").asString());
    }

    private String configurationBody(long tierId, String price) {
        return """
                {"startsAt":"2027-01-15T21:00:00+08:00","admissionOpenAt":"2027-01-15T20:00:00+08:00",
                 "admissionCloseAt":"2027-01-15T20:55:00+08:00","forbidNewOrphan":false,
                 "priceTiers":[{"id":%d,"name":"调整后 A 档","price":%s}],"seats":[]}
                """.formatted(tierId, price);
    }

    private String sessionBody(String code) {
        return """
                {"code":"%s","eventId":%d,"venueId":%d,
                 "startsAt":"2027-01-15T20:00:00+08:00","admissionOpenAt":"2027-01-15T19:00:00+08:00",
                 "admissionCloseAt":"2027-01-15T19:55:00+08:00","forbidNewOrphan":true,
                 "priceTiers":[{"code":"TIER-A","name":"A 档","price":380.00},{"code":"TIER-B","name":"B 档","price":180.00}],
                 "areaPrices":[{"areaId":%d,"priceTierCode":"TIER-A"},{"areaId":%d,"priceTierCode":"TIER-B"}],
                 "seatOverrides":[]}
                """.formatted(code, fixture.event().id(), fixture.venue().id(), fixture.floor().id(), fixture.balcony().id());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            if (body != null) { builder.header("Content-Type", "application/json"); }
            return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body()).path("data");
    }
}
