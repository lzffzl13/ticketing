package com.ticketing.simulator;

import com.ticketing.common.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.ticketing.simulator.RefundSimulatorModels.*;

/** Channel orchestration ends its own transaction before invoking a local result receiver. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RefundSimulatorService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final RefundSimulatorTransactionService transactions;

    public RefundSimulatorService(RefundSimulatorTransactionService transactions) {
        this.transactions = transactions;
    }

    public Refund start(Binding binding) {
        var normalized = normalize(binding);
        try {
            try {
                return transactions.start(normalized);
            } catch (DuplicateKeyException concurrentNumber) {
                // A caller using another payment may win this globally unique refund number.
                // Re-read its binding only after this transaction has fully rolled back.
                return transactions.start(normalized);
            }
        } catch (TransientDataAccessException lockFailure) {
            throw new BusinessException("SIMULATOR_RETRY_REQUIRED", "模拟退款事务已回滚，请使用原退款编号与内容重试",
                    HttpStatus.CONFLICT);
        }
    }

    public Optional<Refund> find(String refundNumber) { return transactions.find(number(refundNumber)); }

    public Refund succeed(String refundNumber) {
        return transactions.finish(number(refundNumber), Status.SUCCEEDED);
    }

    public Refund fail(String refundNumber) {
        return transactions.finish(number(refundNumber), Status.FAILED);
    }

    public List<Notification> notifications(String refundNumber) {
        return transactions.notifications(number(refundNumber));
    }

    /** The receiver must commit before returning; a failure leaves the original notification pending. */
    public void deliver(String notificationNumber, Consumer<Notification> receiver) {
        if (receiver == null) { throw invalid("通知接收者不能为空"); }
        var notification = transactions.notification(number(notificationNumber));
        receiver.accept(notification);
        transactions.acknowledge(notification.notificationNumber());
    }

    private Binding normalize(Binding binding) {
        if (binding == null || binding.amount() == null) {
            throw invalid("退款编号、原支付、商户订单号、原票号与金额不能为空");
        }
        var refundNumber = number(binding.refundNumber());
        var paymentNumber = number(binding.paymentNumber());
        var merchantOrderNumber = number(binding.merchantOrderNumber());
        var ticketNumber = number(binding.ticketNumber());
        var amount = binding.amount();
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("模拟退款金额必须大于零且不超过 9999999999.99");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException extraDecimalPlaces) {
            throw invalid("模拟退款金额最多保留两位有效小数，不进行舍入");
        }
        return new Binding(refundNumber, paymentNumber, merchantOrderNumber, ticketNumber, amount);
    }

    private String number(String value) {
        if (value == null || value.isEmpty() || value.length() > 36
                || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw invalid("编号需为 1–36 位可打印 ASCII 字符且不含空白");
        }
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_SIMULATOR_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
}
