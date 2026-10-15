package com.ticketing.trade;

import com.ticketing.support.TestAuthHelper;
import com.ticketing.ticketing.TicketModels.Ticket;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Every refund starts with the existing real order/payment/issuance flow. */
abstract class RefundTestSupport extends PaymentTestSupport {
    protected PaymentModels.PaymentRecord originalPayment;
    protected List<Ticket> paidTickets;
    protected String ownerToken;
    protected String otherToken;
    protected String operatorToken;
    protected String checkerToken;

    @BeforeEach
    void prepareRefundFixture() throws Exception {
        originalPayment = payment.start(consumer1, order.id(), "refund-original-payment");
        payment.applyChannel(simulator.succeed(originalPayment.paymentNumber()));
        paidTickets = payment.tickets(consumer1, order.id());
        assertEquals(2, paidTickets.size());
        assertPaid(order);
        refreshTokens();
    }

    protected void refreshTokens() throws Exception {
        ownerToken = TestAuthHelper.login(json, port, "consumer1");
        otherToken = TestAuthHelper.login(json, port, "consumer2");
        operatorToken = TestAuthHelper.login(json, port, "operator1");
        checkerToken = TestAuthHelper.login(json, port, "checker1");
    }

    protected JsonNode request(Ticket ticket, String key) throws Exception {
        return data(refundHttp("POST", requestPath(order.id(), ticket.id()), ownerToken,
                Map.of("requestKey", key)));
    }

    protected JsonNode getRefund(JsonNode refund) throws Exception {
        return data(refundHttp("GET", "/api/orders/" + order.id() + "/refunds/" + refund.path("id").asLong(),
                ownerToken, null));
    }

    protected JsonNode orderRefunds() throws Exception {
        return data(refundHttp("GET", "/api/orders/" + order.id() + "/refunds", ownerToken, null));
    }

    protected JsonNode notifications(JsonNode refund) throws Exception {
        return data(refundHttp("GET", "/api/operations/refunds/" + refund.path("refundNumber").asString()
                + "/notifications", operatorToken, null));
    }

    protected String notice(JsonNode refund, String status) throws Exception {
        for (var notification : notifications(refund)) {
            if (status.equals(notification.path("refund").path("refundStatus").asString())) {
                return notification.path("notificationNumber").asString();
            }
        }
        fail("missing persisted " + status + " notification for original refund");
        return null;
    }

    protected void deliver(String notificationNumber) throws Exception {
        data(refundHttp("POST", "/api/operations/refund-notifications/" + notificationNumber + "/deliver",
                operatorToken, null));
    }

    protected JsonNode terminal(JsonNode refund, String status) throws Exception {
        String action = switch (status) {
            case "SUCCEEDED" -> "succeed";
            case "FAILED" -> "fail";
            default -> throw new IllegalArgumentException("test requires a terminal refund status");
        };
        var channel = data(refundHttp("POST", "/api/operations/refunds/"
                + refund.path("refundNumber").asString() + "/" + action, operatorToken, null));
        assertEquals(status, channel.path("refundStatus").asString());
        return channel;
    }

    protected JsonNode finish(JsonNode refund, String status) throws Exception {
        terminal(refund, status);
        deliver(notice(refund, status));
        var result = getRefund(refund);
        assertEquals(status, result.path("refundStatus").asString());
        return result;
    }

    protected void assertTicket(Ticket ticket, String status) {
        assertEquals(status, jdbc.queryForObject("SELECT ticket_status FROM ticket WHERE id=?", String.class,
                ticket.id()));
        assertEquals(ticket.ticketNumber(), jdbc.queryForObject("SELECT ticket_number FROM ticket WHERE id=?",
                String.class, ticket.id()));
    }

    protected void assertSeat(Ticket ticket, String status) {
        var seat = jdbc.queryForMap("SELECT seat_status,current_hold_id FROM session_seat WHERE id=?",
                ticket.sessionSeatId());
        assertEquals(status, seat.get("seat_status"));
        assertNull(seat.get("current_hold_id"));
    }

    protected HttpResponse<String> refundHttp(String method, String path, String token, Object body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token != null) { request.header("Authorization", "Bearer " + token); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    protected JsonNode data(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return json.readTree(response.body()).path("data");
    }

    protected static String requestPath(long orderId, long ticketId) {
        return "/api/orders/" + orderId + "/tickets/" + ticketId + "/refunds";
    }
}
