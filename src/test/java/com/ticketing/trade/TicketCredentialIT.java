package com.ticketing.trade;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.ticketing.common.web.BusinessException;
import com.ticketing.support.TestAuthHelper;
import com.ticketing.ticketing.QrCodeRenderer;
import com.ticketing.ticketing.TicketCredentialCodec;
import com.ticketing.ticketing.TicketModels.Ticket;
import com.ticketing.ticketing.TicketModels.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ticketing.trade.TradeModels.SelectionMode.RECOMMENDED;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/** Real payment/issuance plus owner-only credentials; refund/check-in statuses below are explicit SQL fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class TicketCredentialIT extends PaymentTestSupport {
    @Autowired private TicketCredentialService credentials;
    @Autowired private TicketCredentialCodec codec;
    @MockitoSpyBean private QrCodeRenderer images;

    @Test
    void ownerGetsTheExistingPaidTicketAsTextAndDecodablePngWithoutChangingItsFacts() throws Exception {
        var original = payAndGetTicket();
        String token = TestAuthHelper.login(json, port, "consumer1");
        var before = businessFacts();

        var response = http("GET", path(order.id(), original.id(), "credential"), token, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var data = json.readTree(response.body()).path("data");
        assertEquals(original.id().longValue(), data.path("ticketId").asLong());
        assertEquals(original.ticketNumber(), data.path("ticketNumber").asString());
        assertEquals("VALID", data.path("ticketStatus").asString());
        String credential = data.path("credential").asString();
        assertEquals(original.ticketNumber(), codec.verify(credential));
        assertNotEquals(original.ticketNumber(), credential);

        var png = image(path(order.id(), original.id(), "qrcode"), token);
        assertEquals(200, png.statusCode());
        assertEquals("image/png", png.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", png.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(credential, decode(png.body()));
        assertEquals(before, businessFacts(), "querying a paid ticket must not create or update business facts");
        assertPaid(order);
    }

    @Test
    void repeatedAndConcurrentQueriesKeepTheSameCredentialAndAllRowsIncludingTimestamps() throws Exception {
        var original = payAndGetTicket();
        var expected = credentials.credential(consumer1, order.id(), original.id());
        var before = businessFacts();
        for (int index = 0; index < 3; index++) {
            assertEquals(expected, credentials.credential(consumer1, order.id(), original.id()));
        }
        var results = compete(
                () -> credentials.credential(consumer1, order.id(), original.id()),
                () -> credentials.credential(consumer1, order.id(), original.id()));
        assertEquals(List.of(expected, expected), results);
        assertEquals(expected.credential(), decode(credentials.png(consumer1, order.id(), original.id())));
        assertEquals(before, businessFacts());
        assertEquals(2, count("ticket"));
        assertEquals(1, count("payment_record"));
    }

    @Test
    void bothEndpointsEnforceAuthenticationRolesOwnershipAndExactOrderTicketBinding() throws Exception {
        var original = payAndGetTicket();
        var otherOrder = newOrder("credential-other-order", "FLOOR-A", "B");
        var attempt = payment.start(consumer1, otherOrder.id(), "credential-other-payment");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        var otherTicket = payment.tickets(consumer1, otherOrder.id()).getFirst();
        String owner = TestAuthHelper.login(json, port, "consumer1");
        String stranger = TestAuthHelper.login(json, port, "consumer2");
        String operator = TestAuthHelper.login(json, port, "operator1");
        String checker = TestAuthHelper.login(json, port, "checker1");
        var before = businessFacts();

        for (String endpoint : List.of("credential", "qrcode")) {
            String requested = path(order.id(), original.id(), endpoint);
            assertEquals(401, http("GET", requested, null, null).statusCode());
            assertEquals(403, http("GET", requested, operator, null).statusCode());
            assertEquals(403, http("GET", requested, checker, null).statusCode());
            assertNotFound(requested, stranger);
            assertNotFound(path(order.id(), otherTicket.id(), endpoint), owner);
            assertNotFound(path(otherOrder.id(), original.id(), endpoint), owner);
            assertNotFound(path(Long.MAX_VALUE, original.id(), endpoint), owner);
            assertNotFound(path(order.id(), Long.MAX_VALUE, endpoint), owner);
        }
        assertEquals(before, businessFacts());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"REFUNDING", "REFUNDED", "CHECKED_IN"})
    void explicitNonValidStatusFixtureBlocksBothCredentialsButKeepsTheHistoricalTicketVisible(TicketStatus state)
            throws Exception {
        var original = payAndGetTicket();
        String oldCredential = credentials.credential(consumer1, order.id(), original.id()).credential();
        // This is a state-gate fixture, not a call to an implemented refund or check-in operation.
        jdbc.update("UPDATE ticket SET ticket_status=?,updated_at=? WHERE id=?",
                state.name(), Timestamp.from(NOW.plusSeconds(1)), original.id());
        String token = TestAuthHelper.login(json, port, "consumer1");
        var before = businessFacts();

        for (String endpoint : List.of("credential", "qrcode")) {
            var response = http("GET", path(order.id(), original.id(), endpoint), token, null);
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("TICKET_NOT_VALID", json.readTree(response.body()).path("code").asString());
            assertFalse(response.body().contains(oldCredential));
        }
        var historical = payment.tickets(consumer1, order.id()).stream()
                .filter(ticket -> ticket.id().equals(original.id())).findFirst().orElseThrow();
        assertEquals(state, historical.ticketStatus());
        assertEquals(original.ticketNumber(), codec.verify(oldCredential),
                "authenticity alone cannot grant admission to a non-VALID ticket");
        assertEquals(before, businessFacts());
    }

    @Test
    void explicitRefundFailureFixtureRestoresTheOriginalTicketAndItsUnrevokedCredential() {
        var original = payAndGetTicket();
        var oldView = credentials.credential(consumer1, order.id(), original.id());
        // Only the agreed ticket states are installed here; no refund-channel functionality is claimed.
        jdbc.update("UPDATE ticket SET ticket_status='REFUNDING' WHERE id=?", original.id());
        assertConflict("TICKET_NOT_VALID", () -> credentials.credential(consumer1, order.id(), original.id()));
        jdbc.update("UPDATE ticket SET ticket_status='VALID' WHERE id=?", original.id());
        var before = businessFacts();
        assertEquals(oldView, credentials.credential(consumer1, order.id(), original.id()));
        assertEquals(original.ticketNumber(), codec.verify(oldView.credential()));
        assertEquals(before, businessFacts());
        assertPaid(order);
    }

    @Test
    void advanceDisplayIsAllowedButTheExactAdmissionCloseAndLaterAreRejectedWithoutChangingTicketState()
            throws Exception {
        var original = payAndGetTicket();
        var session = sessions.get(sessionId);
        assertTrue(NOW.isBefore(session.admissionOpenAt()));
        String early = credentials.credential(consumer1, order.id(), original.id()).credential();
        var before = businessFacts();
        clock.set(session.admissionOpenAt());
        assertEquals(early, credentials.credential(consumer1, order.id(), original.id()).credential());
        clock.set(session.admissionCloseAt().minusNanos(1_000));
        assertEquals(early, credentials.credential(consumer1, order.id(), original.id()).credential());

        clock.set(session.admissionCloseAt());
        // A fresh login separates the admission cutoff from the independent two-hour login lifetime.
        String token = TestAuthHelper.login(json, port, "consumer1");
        for (String endpoint : List.of("credential", "qrcode")) {
            var response = http("GET", path(order.id(), original.id(), endpoint), token, null);
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("TICKET_ADMISSION_CLOSED", json.readTree(response.body()).path("code").asString());
        }
        clock.set(session.admissionCloseAt().plusNanos(1_000));
        assertConflict("TICKET_ADMISSION_CLOSED", () -> credentials.credential(consumer1, order.id(), original.id()));
        assertEquals(TicketStatus.VALID, payment.tickets(consumer1, order.id()).getFirst().ticketStatus());
        assertEquals(original.ticketNumber(), codec.verify(early));
        assertEquals(before, businessFacts());
    }

    @Test
    void refundedOldTicketFixtureAndNormalResaleKeepOldAndNewCredentialsBoundToDifferentOriginalTickets() {
        var original = payAndGetTicket();
        String oldCredential = credentials.credential(consumer1, order.id(), original.id()).credential();
        // This atomically installs a completed-refund fixture. The refund entry point is a later increment.
        transaction().executeWithoutResult(status -> {
            jdbc.update("UPDATE ticket SET ticket_status='REFUNDED' WHERE order_id=?", order.id());
            jdbc.update("UPDATE customer_order SET order_status='FULLY_REFUNDED' WHERE id=?", order.id());
            jdbc.update("""
                    UPDATE session_seat s JOIN order_item i ON i.session_seat_id=s.id
                       SET s.seat_status='AVAILABLE',s.current_hold_id=NULL WHERE i.order_id=?
                    """, order.id());
        });
        var resale = orders.create(consumer2, "credential-resale-order", new CreateOrder(sessionId, RECOMMENDED,
                order.items().stream().map(TradeModels.OrderItem::sessionSeatId).toList())).order();
        var newPayment = payment.start(consumer2, resale.id(), "credential-resale-payment");
        payment.applyChannel(simulator.succeed(newPayment.paymentNumber()));
        var replacement = payment.tickets(consumer2, resale.id()).stream()
                .filter(ticket -> ticket.sessionSeatId().equals(original.sessionSeatId())).findFirst().orElseThrow();
        var before = businessFacts();
        String replacementCredential = credentials.credential(consumer2, resale.id(), replacement.id()).credential();

        assertNotEquals(original.id(), replacement.id());
        assertNotEquals(original.ticketNumber(), replacement.ticketNumber());
        assertEquals(original.sessionSeatId(), replacement.sessionSeatId());
        assertEquals(original.ticketNumber(), codec.verify(oldCredential));
        assertEquals(replacement.ticketNumber(), codec.verify(replacementCredential));
        assertConflict("TICKET_NOT_VALID", () -> credentials.credential(consumer1, order.id(), original.id()));
        assertEquals("SUCCEEDED", payment.payments(consumer1, order.id()).getFirst().paymentStatus().name());
        assertEquals(TicketStatus.REFUNDED, payment.tickets(consumer1, order.id()).getFirst().ticketStatus());
        assertEquals(before, businessFacts());
    }

    @Test
    void imageFailureAfterTheReadTransactionDoesNotAffectIssuanceAndAFixedRendererCanBeRetried() throws Exception {
        var original = payAndGetTicket();
        String token = TestAuthHelper.login(json, port, "consumer1");
        String expected = credentials.credential(consumer1, order.id(), original.id()).credential();
        var before = businessFacts();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "image generation must happen after the authorized read transaction has ended");
            throw new IllegalStateException("test image encoder unavailable");
        }).when(images).render(anyString());
        try {
            var failed = http("GET", path(order.id(), original.id(), "qrcode"), token, null);
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals("INTERNAL_ERROR", json.readTree(failed.body()).path("code").asString());
            assertFalse(failed.body().contains(expected));
            assertFalse(failed.body().contains("test image encoder unavailable"));
            assertEquals(before, businessFacts());
            assertPaid(order);
            assertEquals(expected, credentials.credential(consumer1, order.id(), original.id()).credential());
        } finally {
            doCallRealMethod().when(images).render(anyString());
        }
        var retried = image(path(order.id(), original.id(), "qrcode"), token);
        assertEquals(200, retried.statusCode());
        assertEquals(expected, decode(retried.body()));
        assertEquals(before, businessFacts());
    }

    private Ticket payAndGetTicket() {
        var attempt = payment.start(consumer1, order.id(), "credential-original-payment");
        payment.applyChannel(simulator.succeed(attempt.paymentNumber()));
        assertPaid(order);
        return payment.tickets(consumer1, order.id()).getFirst();
    }

    private static String path(long orderId, long ticketId, String endpoint) {
        return "/api/orders/" + orderId + "/tickets/" + ticketId + "/" + endpoint;
    }

    private void assertNotFound(String path, String token) throws Exception {
        var response = http("GET", path, token, null);
        assertEquals(404, response.statusCode(), response.body());
        assertEquals("NOT_FOUND", json.readTree(response.body()).path("code").asString());
    }

    private void assertConflict(String code, Runnable operation) {
        var failure = assertThrows(BusinessException.class, operation::run);
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(code, failure.getCode());
    }

    /** The complete persisted rows include IDs, statuses, original deadlines and every update timestamp. */
    private Map<String, List<Map<String, Object>>> businessFacts() {
        var facts = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("ticket", "payment_record", "customer_order", "seat_hold", "order_item",
                "session_seat")) {
            facts.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        facts.put("simulated_payment", jdbc.queryForList("SELECT * FROM simulated_payment ORDER BY payment_number"));
        facts.put("simulated_notification", jdbc.queryForList("SELECT * FROM simulated_notification ORDER BY notification_number"));
        facts.put("seat_hold_item", jdbc.queryForList("SELECT * FROM seat_hold_item ORDER BY hold_id,session_seat_id"));
        return facts;
    }

    private HttpResponse<byte[]> image(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Bearer " + token).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private String decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "the response must be a readable PNG");
        int width = image.getWidth();
        int height = image.getHeight();
        var source = new RGBLuminanceSource(width, height, image.getRGB(0, 0, width, height, null, 0, width));
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
