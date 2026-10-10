package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.simulator.PaymentSimulatorModels.Binding;
import com.ticketing.simulator.PaymentSimulatorModels.Payment;
import com.ticketing.simulator.PaymentSimulatorModels.PaymentStatus;
import com.ticketing.simulator.PaymentSimulatorService;
import com.ticketing.ticketing.TicketModels.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;
import static com.ticketing.trade.PaymentModels.*;

/** Durable intent -> independent channel -> committed local result, with no channel call under local locks. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final PaymentTransactionService writer;
    private final PaymentQueryService queries;
    private final PaymentSimulatorService simulator;
    private final Clock clock;
    public PaymentService(PaymentTransactionService writer, PaymentQueryService queries,
                           PaymentSimulatorService simulator, Clock clock) {
        this.writer = writer; this.queries = queries; this.simulator = simulator; this.clock = clock;
    }

    public PaymentRecord start(long customerId, long orderId, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "请求编号需为 1–64 位字母、数字、连字符或下划线", HttpStatus.BAD_REQUEST);
        }
        var beginning = writer.begin(customerId, orderId, key);
        if (beginning.work() != null) { process(beginning.work()); }
        return queries.payment(beginning.payment().paymentNumber());
    }
    public List<PaymentRecord> payments(long customerId, long orderId) { return queries.payments(customerId, orderId); }
    public List<Ticket> tickets(long customerId, long orderId) { return queries.tickets(customerId, orderId); }
    public OrderPaymentState state(long orderId) { return queries.state(orderId); }
    public OrderPaymentState cancel(long customerId, long orderId) {
        var work = writer.cancel(customerId, orderId);
        if (work != null) { process(work); }
        return queries.ownedState(customerId, orderId);
    }
    public OrderPaymentState recover(long orderId) {
        var work = writer.claim(orderId, true);
        if (work != null) { process(work); }
        return queries.state(orderId);
    }

    // Used by week-five scanning: observes backoff/manual intervention and shares the same lease protocol.
    public OrderPaymentState recoverIfDue(long orderId) {
        var work = writer.claim(orderId, false);
        if (work != null) { process(work); }
        return queries.state(orderId);
    }

    public PaymentRecord applyChannel(Payment channel) {
        try { return writer.applyChannel(channel); }
        catch (BusinessException error) {
            if ("PAYMENT_FACT_CONFLICT".equals(error.getCode())) {
                writer.markConflict(queries.payment(channel.paymentNumber()).orderId());
                log.warn("payment_fact_conflict paymentNumber={} orderId={}", channel.paymentNumber(),
                        queries.payment(channel.paymentNumber()).orderId());
            }
            throw error;
        }
    }

    public void deliver(String notificationNumber) {
        simulator.deliver(notificationNumber, notice -> {
            var local = queries.payment(notice.paymentNumber());
            var ownership = writer.claim(local.orderId(), true);
            String phase = "CHANNEL_UNAVAILABLE";
            try {
                var current = simulator.find(notice.paymentNumber()).orElseThrow();
                // Preserve the saved observation; old PROCESSING must not become a newer success.
                var observed = new Payment(current.paymentNumber(), current.merchantOrderNumber(), current.amount(),
                        current.paymentExpiresAt(), notice.observedStatus(), current.acceptedAt(),
                        notice.observedStatus() == PaymentStatus.PROCESSING ? null : current.resultAt(),
                        notice.observedStatus() == PaymentStatus.FAILED ? current.failureCode() : null,
                        current.createdAt(), notice.observedAt());
                phase = "LOCAL_APPLY_FAILED";
                applyChannel(observed); // Result commits before receiver returns and the simulator acknowledges.
                if (ownership != null) {
                    boolean observesCurrent = notice.observedStatus() == current.paymentStatus()
                            && ownership.payments().stream().filter(p -> !p.paymentStatus().terminal())
                            .allMatch(p -> p.paymentNumber().equals(notice.paymentNumber()));
                    if (observesCurrent) { writer.complete(local.orderId(), ownership.token()); }
                    else { writer.releaseClaim(local.orderId(), ownership.token()); }
                }
            } catch (RuntimeException failure) {
                var error = failure instanceof BusinessException business && "PAYMENT_FACT_CONFLICT".equals(business.getCode())
                        ? "PAYMENT_FACT_CONFLICT" : phase;
                if (ownership != null) { writer.fail(local.orderId(), ownership.token(), error); }
                else { writer.noteResultFailure(local.orderId(), error); }
                throw failure; // No delivered_at acknowledgement for a failed receiver.
            }
        });
    }

    private void process(Work work) {
        String phase = "CHANNEL_UNAVAILABLE";
        try {
            for (var local : work.payments()) {
                var order = queries.order(work.order().id());
                if (!clock.instant().isBefore(order.paymentExpiresAt())) {
                    writer.markTimeout(order.id());
                    order = queries.order(order.id());
                }
                var binding = new Binding(local.paymentNumber(), order.orderNumber(), local.amount(), local.paymentExpiresAt());
                var found = simulator.find(local.paymentNumber());
                Payment channel;
                if (found.isEmpty()) {
                    if (local.paymentStatus().terminal()) {
                        throw new BusinessException("PAYMENT_FACT_CONFLICT", "本地终态对应的渠道事实缺失，需要人工核查", HttpStatus.CONFLICT);
                    }
                    channel = order.cancellationRequestedAt() != null ? simulator.close(binding) : simulator.start(binding);
                } else { channel = found.orElseThrow(); }
                // Re-read cancellation/deadline after the external query, as either can change during a call.
                order = queries.order(order.id());
                if (!clock.instant().isBefore(order.paymentExpiresAt())) {
                    writer.markTimeout(order.id());
                    order = queries.order(order.id());
                }
                if (channel.paymentStatus() == PaymentStatus.PROCESSING && order.cancellationRequestedAt() != null) {
                    channel = simulator.close(binding);
                }
                phase = "LOCAL_APPLY_FAILED";
                applyChannel(channel);
                phase = "CHANNEL_UNAVAILABLE";
            }
            phase = "LOCAL_APPLY_FAILED";
            writer.complete(work.order().id(), work.token());
        } catch (RuntimeException failure) {
            String category = failure instanceof BusinessException business && "PAYMENT_FACT_CONFLICT".equals(business.getCode())
                    ? "PAYMENT_FACT_CONFLICT" : phase;
            writer.fail(work.order().id(), work.token(), category);
            // Persisted category plus original payment/notification are recovery evidence; avoid secrets/raw payloads.
            log.warn("payment_recovery_pending orderId={} category={}", work.order().id(), category);
        }
    }
}
