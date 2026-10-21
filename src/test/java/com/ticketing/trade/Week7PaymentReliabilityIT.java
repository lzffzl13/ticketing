package com.ticketing.trade;

import com.ticketing.consistency.ConsistencyService;
import com.ticketing.simulator.PaymentSimulatorModels.Notification;
import com.ticketing.simulator.PaymentSimulatorService;
import com.ticketing.simulator.PaymentSimulatorTransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ticketing.trade.TradeModels.OrderStatus.CANCELLED;
import static com.ticketing.trade.TradeModels.OrderStatus.PAID;
import static com.ticketing.trade.TradeModels.SelectionMode.RECOMMENDED;
import static com.ticketing.trade.TradeRequests.CreateOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * Combined fault experiments against the real services and isolated MySQL database.
 * Latches simulate a stalled worker; exceptions simulate lost acknowledgement or local write failure.
 * These are deterministic in-process experiments, not evidence of an actual process kill.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PaymentTestSupport.TestClock.class, PaymentTestSupport.RealMapperSpies.class})
class Week7PaymentReliabilityIT extends PaymentTestSupport {
    private static final Path REPORT = Path.of("target", "week7-payment-reliability.json");
    private static final List<Map<String, Object>> EXPERIMENTS = new ArrayList<>();

    @Autowired private ConsistencyService consistency;
    @MockitoSpyBean private PaymentQueryService recoveryQueries;
    @MockitoSpyBean private PaymentSimulatorTransactionService channelTransactions;
    private Map<String, Object> experiment;
    private List<Map<String, Object>> stages;

    @BeforeAll
    static void beginReport() throws Exception {
        EXPERIMENTS.clear();
        Files.deleteIfExists(REPORT);
    }

    @AfterEach
    void saveEvidence() throws Exception {
        if (experiment == null) { return; }
        experiment.put("finalConsistency", consistency.inspect(sessionId));
        EXPERIMENTS.add(experiment);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "experimentType", "real services and MySQL with controlled in-process fault injection",
                "recordedAt", Instant.now(), "experiments", EXPERIMENTS)), StandardCharsets.UTF_8);
    }

    @Test
    void committedTicketsSurviveLostAcknowledgementConcurrentRedeliveryAndAnOlderProcessingNotice() throws Exception {
        begin("committed-result-before-notice-acknowledgement",
                "Throw before the first simulator acknowledgement, after the receiver has committed local success.");
        var attempt = payment.start(consumer1, order.id(), "w7-ack-window");
        var processing = notice(attempt.paymentNumber(), "PROCESSING");
        simulator.succeed(attempt.paymentNumber());
        var success = notice(attempt.paymentNumber(), "SUCCEEDED");
        var acknowledgements = new AtomicInteger();
        var target = AopTestUtils.<PaymentSimulatorTransactionService>getUltimateTargetObject(channelTransactions);
        doAnswer(call -> {
            if (acknowledgements.incrementAndGet() == 1) {
                throw new IllegalStateException("injected interruption after local commit before notice acknowledgement");
            }
            return call.callRealMethod();
        }).when(target).acknowledge(eq(success.notificationNumber()));

        assertThrows(IllegalStateException.class, () -> payment.deliver(success.notificationNumber()));
        assertPaid(order);
        var issued = ticketIds(order.id());
        checkpoint("local commit survives acknowledgement failure",
                Map.of("orderStatus", "PAID", "tickets", 2, "noticeDelivered", false, "failures", 0),
                Map.of("orderStatus", payment.state(order.id()).orderStatus().name(), "tickets", issued.size(),
                        "noticeDelivered", delivered(success), "failures", payment.state(order.id()).consecutiveFailures()));

        clock.set(NOW.plusSeconds(1));
        var selected = new AtomicInteger();
        compete(() -> {
            payment.deliver(success.notificationNumber());
            return "REDELIVERED";
        }, () -> {
            selected.addAndGet(scanner().scanOnce());
            payment.deliver(processing.notificationNumber());
            selected.addAndGet(scanner().scanOnce());
            return "OLD_NOTICE_AND_REPEATED_TASK";
        });
        var firstAcknowledgedAt = notice(attempt.paymentNumber(), "SUCCEEDED").deliveredAt();
        assertNotNull(firstAcknowledgedAt);
        clock.set(NOW.plusSeconds(2));
        payment.deliver(success.notificationNumber());
        payment.deliver(simulator.createNotification(attempt.paymentNumber()).notificationNumber());

        checkpoint("duplicate and old notices preserve the original tickets and acknowledgement time",
                Map.of("sameTicketIds", true, "successDelivered", true, "oldProcessingDelivered", true,
                        "selectedPaidOrders", 0, "firstAcknowledgementPreserved", true, "failures", 0),
                Map.of("sameTicketIds", issued.equals(ticketIds(order.id())), "successDelivered", delivered(success),
                        "oldProcessingDelivered", delivered(processing), "selectedPaidOrders", selected.get(),
                        "firstAcknowledgementPreserved", firstAcknowledgedAt.equals(notice(attempt.paymentNumber(), "SUCCEEDED").deliveredAt()),
                        "failures", payment.state(order.id()).consecutiveFailures()));
        assertPaid(order);
        finish();
    }

    @Test
    void staleWorkerCannotClearTakeoverClaimAndTwoRetryWorkersRecoverTheTakeoversActualTicketRollback() throws Exception {
        begin("expired-worker-old-snapshot-and-replacement-write-failure",
                "Latch after a real PROCESSING query; expire its lease with the test Clock. A replacement queries real success, "
                        + "then throws after the first actual ticket INSERT. No table facts are fabricated.");
        var attempt = payment.start(consumer1, order.id(), "w7-takeover-rollback");
        var processing = notice(attempt.paymentNumber(), "PROCESSING");
        clock.set(payment.state(order.id()).nextRecoveryAt());
        var oldStartedAt = clock.instant();
        var oldEntered = new CountDownLatch(1);
        var replacementEntered = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var releaseReplacement = new CountDownLatch(1);
        var channelQueries = new AtomicInteger();
        doAnswer(call -> {
            var actualSnapshot = call.callRealMethod();
            int invocation = channelQueries.incrementAndGet();
            if (invocation == 1) { oldEntered.countDown(); await(releaseOld); }
            if (invocation == 2) { replacementEntered.countDown(); await(releaseReplacement); }
            return actualSnapshot;
        }).when(channelTarget()).find(eq(attempt.paymentNumber()));
        failAfterFirstActualTicketInsert();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var oldWorker = pool.submit(() -> payment.recoverIfDue(order.id()));
            try {
                assertTrue(oldEntered.await(10, TimeUnit.SECONDS));
                var originalToken = storedToken();
                clock.set(oldStartedAt.plusSeconds(61));
                simulator.succeed(attempt.paymentNumber());
                var replacementWorker = pool.submit(() -> payment.recoverIfDue(order.id()));
                try {
                    assertTrue(replacementEntered.await(10, TimeUnit.SECONDS));
                    var replacementToken = storedToken();
                    assertNotNull(replacementToken);
                    assertNotEquals(originalToken, replacementToken);
                    checkpoint("expired lease counted once while replacement owns a fresh claim",
                            Map.of("failures", 1, "error", "RECOVERY_LEASE_EXPIRED", "leaseActive", true, "channelQueries", 2),
                            Map.of("failures", payment.state(order.id()).consecutiveFailures(), "error", payment.state(order.id()).recentError(),
                                    "leaseActive", payment.state(order.id()).recoveryClaimUntil().isAfter(clock.instant()),
                                    "channelQueries", channelQueries.get()));

                    releaseOld.countDown();
                    oldWorker.get(20, TimeUnit.SECONDS);
                    assertEquals(replacementToken, storedToken(), "an old completion cannot clear the new worker's token");
                    checkpoint("old PROCESSING result returns without clearing replacement ownership",
                            Map.of("replacementStillOwnsClaim", true, "failures", 1, "localPayment", "PROCESSING", "tickets", 0),
                            Map.of("replacementStillOwnsClaim", replacementToken.equals(storedToken()),
                                    "failures", payment.state(order.id()).consecutiveFailures(),
                                    "localPayment", payment.state(order.id()).payments().getFirst().paymentStatus().name(),
                                    "tickets", ticketIds(order.id()).size()));
                } finally { releaseReplacement.countDown(); }
                replacementWorker.get(20, TimeUnit.SECONDS);
            } finally { releaseOld.countDown(); releaseReplacement.countDown(); }
        }

        assertHeld(order);
        var failed = payment.state(order.id());
        checkpoint("replacement INSERT fault rolls back the complete local result and adds one failure",
                Map.of("failures", 2, "error", "LOCAL_APPLY_FAILED", "tickets", 0, "claimCleared", true,
                        "retryAt", clock.instant().plusSeconds(15), "firstUnresolvedAt", oldStartedAt.plusSeconds(60),
                        "channelStatus", "SUCCEEDED", "prematureScanCandidates", 0),
                Map.of("failures", failed.consecutiveFailures(), "error", failed.recentError(), "tickets", ticketIds(order.id()).size(),
                        "claimCleared", failed.recoveryClaimUntil() == null, "retryAt", failed.nextRecoveryAt(),
                        "firstUnresolvedAt", failed.firstUnresolvedAt(),
                        "channelStatus", jdbc.queryForObject("SELECT payment_status FROM simulated_payment WHERE payment_number=?", String.class, attempt.paymentNumber()),
                        "prematureScanCandidates", scanner().scanOnce()));

        clock.set(failed.nextRecoveryAt());
        // Two independent scanner instances deliberately read the same committed candidate before either claims it.
        var selectedTogether = new CyclicBarrier(2);
        var selections = new AtomicInteger();
        doAnswer(call -> {
            var candidates = call.callRealMethod();
            if (selections.incrementAndGet() <= 2) { selectedTogether.await(10, TimeUnit.SECONDS); }
            return candidates;
        }).when(AopTestUtils.<PaymentQueryService>getUltimateTargetObject(recoveryQueries))
                .dueRecoveryOrders(any(Instant.class), anyLong(), anyLong(), anyInt());
        assertEquals(List.of(1, 1), compete(() -> scanner().scanOnce(), () -> scanner().scanOnce()));
        assertPaid(order);
        checkpoint("two retry scanners select one order and perform only one new channel query",
                Map.of("channelQueries", 3, "tickets", 2, "failures", 0, "claimCleared", true, "noticeStillPending", true),
                Map.of("channelQueries", channelQueries.get(), "tickets", ticketIds(order.id()).size(),
                        "failures", payment.state(order.id()).consecutiveFailures(),
                        "claimCleared", payment.state(order.id()).recoveryClaimUntil() == null,
                        "noticeStillPending", !delivered(notice(attempt.paymentNumber(), "SUCCEEDED"))));

        var issued = ticketIds(order.id());
        payment.deliver(processing.notificationNumber());
        var success = notice(attempt.paymentNumber(), "SUCCEEDED");
        payment.deliver(success.notificationNumber());
        payment.deliver(success.notificationNumber());
        checkpoint("late original notices are acknowledged without repeating the recovered ticket writes",
                Map.of("sameTicketIds", true, "oldProcessingDelivered", true, "successDelivered", true, "failures", 0),
                Map.of("sameTicketIds", issued.equals(ticketIds(order.id())), "oldProcessingDelivered", delivered(processing),
                        "successDelivered", delivered(success), "failures", payment.state(order.id()).consecutiveFailures()));
        finish();
    }

    @Test
    void delayedFailedAndProcessingNoticesOfAnExpiredOrderCannotAffectTheSeatsNewPaidOwner() throws Exception {
        begin("historical-notices-after-cancellation-and-resale",
                "Delay persisted PROCESSING/FAILED notifications until the original failed order is safely expired "
                        + "and consumer2 has bought the same two seats; replay old notices and the old recovery task concurrently.");
        var originalAttempt = payment.start(consumer1, order.id(), "w7-original-failed");
        var processing = notice(originalAttempt.paymentNumber(), "PROCESSING");
        payment.applyChannel(simulator.fail(originalAttempt.paymentNumber()));
        var failure = notice(originalAttempt.paymentNumber(), "FAILED");
        clock.set(order.paymentExpiresAt());
        assertEquals(1, scanner().scanOnce());
        assertCancelled(order, "EXPIRED");
        checkpoint("failed original order safely expires before any resale",
                Map.of("orderStatus", "CANCELLED", "paymentStatus", "FAILED", "tickets", 0, "failureNoticePending", true),
                Map.of("orderStatus", payment.state(order.id()).orderStatus().name(),
                        "paymentStatus", payment.state(order.id()).payments().getFirst().paymentStatus().name(),
                        "tickets", ticketIds(order.id()).size(), "failureNoticePending", !delivered(failure)));

        var selectedSeats = order.items().stream().map(TradeModels.OrderItem::sessionSeatId).toList();
        var replacement = orders.create(consumer2, "w7-resale-other-customer",
                new CreateOrder(sessionId, RECOMMENDED, selectedSeats)).order();
        var replacementAttempt = payment.start(consumer2, replacement.id(), "w7-resale-payment");
        payment.applyChannel(simulator.succeed(replacementAttempt.paymentNumber()));
        var newTickets = ticketIds(replacement.id());
        experiment.put("replacementOrderId", replacement.id());
        experiment.put("replacementOrderNumber", replacement.orderNumber());
        experiment.put("replacementPaymentNumber", replacementAttempt.paymentNumber());

        compete(() -> {
            payment.deliver(failure.notificationNumber());
            payment.deliver(processing.notificationNumber());
            payment.deliver(failure.notificationNumber());
            return "OLD_NOTICES";
        }, () -> {
            assertEquals(CANCELLED, payment.recoverIfDue(order.id()).orderStatus());
            assertEquals(CANCELLED, payment.recover(order.id()).orderStatus());
            assertEquals(0, scanner().scanOnce());
            return "OLD_RECOVERY_TASKS";
        });
        assertEquals(PAID, orders.get(consumer2, replacement.id()).orderStatus());
        assertEquals("CONSUMED", orders.get(consumer2, replacement.id()).hold().status().name());
        assertEquals("EXPIRED", orders.get(consumer1, order.id()).hold().status().name());
        assertEquals(0, payment.state(order.id()).consecutiveFailures());
        assertEquals(0, payment.state(replacement.id()).consecutiveFailures());
        checkpoint("late old notices and repeated recovery preserve the new buyer's sold inventory and original tickets",
                Map.of("oldOrderStatus", "CANCELLED", "oldPaymentStatus", "FAILED", "newOrderStatus", "PAID",
                        "newPaymentStatus", "SUCCEEDED", "sameNewTicketIds", true, "newTickets", 2,
                        "originalTickets", 0, "soldWithNoHold", 2, "bothOldNoticesDelivered", true),
                Map.of("oldOrderStatus", payment.state(order.id()).orderStatus().name(),
                        "oldPaymentStatus", payment.state(order.id()).payments().getFirst().paymentStatus().name(),
                        "newOrderStatus", payment.state(replacement.id()).orderStatus().name(),
                        "newPaymentStatus", payment.state(replacement.id()).payments().getFirst().paymentStatus().name(),
                        "sameNewTicketIds", newTickets.equals(ticketIds(replacement.id())), "newTickets", newTickets.size(),
                        "originalTickets", ticketIds(order.id()).size(),
                        "soldWithNoHold", jdbc.queryForObject("SELECT COUNT(*) FROM session_seat s JOIN order_item i ON i.session_seat_id=s.id WHERE i.order_id=? AND s.seat_status='SOLD' AND s.current_hold_id IS NULL", Integer.class, replacement.id()),
                        "bothOldNoticesDelivered", delivered(failure) && delivered(processing)));
        finish();
    }

    private void begin(String id, String faultSource) {
        stages = new ArrayList<>();
        experiment = new LinkedHashMap<>();
        experiment.put("id", id);
        experiment.put("faultSource", faultSource);
        experiment.put("originalOrderId", order.id());
        experiment.put("originalOrderNumber", order.orderNumber());
        experiment.put("sessionId", sessionId);
        experiment.put("selectedSeatIds", order.items().stream().map(TradeModels.OrderItem::sessionSeatId).toList());
        experiment.put("assertionsCompleted", false);
        experiment.put("stages", stages);
    }

    private void checkpoint(String name, Map<String, Object> expected, Map<String, Object> actual) {
        var stage = new LinkedHashMap<String, Object>();
        stage.put("name", name);
        stage.put("testClock", clock.instant());
        stage.put("expected", expected);
        stage.put("actual", actual);
        stage.put("originalOrderState", payment.state(order.id()));
        // Public notification DTOs contain identities, observations and delivery times, never a recovery token.
        stage.put("originalNotifications", payment.payments(consumer1, order.id()).stream()
                .flatMap(p -> simulator.notifications(p.paymentNumber()).stream()).toList());
        stages.add(stage);
        assertEquals(expected, actual, name);
    }

    private void finish() {
        var report = consistency.inspect(sessionId);
        assertTrue(report.consistent(), report.differences().toString());
        assertTrue(report.differences().isEmpty());
        assertEquals(0, report.recovery().pendingOrders());
        assertEquals(0, report.recovery().unresolvedPayments());
        assertEquals(0, report.recovery().manualOrders());
        experiment.put("assertionsCompleted", true);
    }

    private Notification notice(String paymentNumber, String status) {
        return simulator.notifications(paymentNumber).stream()
                .filter(n -> n.observedStatus().name().equals(status)).findFirst().orElseThrow();
    }

    private boolean delivered(Notification notification) {
        return simulator.notifications(notification.paymentNumber()).stream()
                .filter(n -> n.notificationNumber().equals(notification.notificationNumber()))
                .findFirst().orElseThrow().deliveredAt() != null;
    }

    private List<Long> ticketIds(long orderId) {
        return jdbc.queryForList("SELECT id FROM ticket WHERE order_id=? ORDER BY id", Long.class, orderId);
    }

    private String storedToken() {
        return jdbc.queryForObject("SELECT recovery_token FROM customer_order WHERE id=?", String.class, order.id());
    }

    private PaymentSimulatorService channelTarget() { return AopTestUtils.getUltimateTargetObject(simulator); }
    private PaymentRecoveryScanner scanner() { return new PaymentRecoveryScanner(recoveryQueries, payment, clock, 50); }
}
