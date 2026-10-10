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
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.ticketing.simulator.PaymentSimulatorModels.*;

/** Transaction-free orchestration; callers must not hold inventory locks while invoking the channel. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentSimulatorService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final PaymentSimulatorTransactionService transactions;

    public PaymentSimulatorService(PaymentSimulatorTransactionService transactions) {
        this.transactions = transactions;
    }

    public Payment start(Binding binding) { return open(normalize(binding), false); }

    public Optional<Payment> find(String paymentNumber) { return transactions.find(number(paymentNumber)); }

    public Payment close(Binding binding) { return open(normalize(binding), true); }

    public Payment succeed(String paymentNumber) {
        return transactions.finish(number(paymentNumber), PaymentStatus.SUCCEEDED);
    }

    public Payment fail(String paymentNumber) {
        return transactions.finish(number(paymentNumber), PaymentStatus.FAILED);
    }

    public List<Notification> notifications(String paymentNumber) {
        return transactions.notifications(number(paymentNumber));
    }

    public Notification createNotification(String paymentNumber) {
        return transactions.createNotification(number(paymentNumber));
    }

    /** The receiver must commit its own result transaction before returning; throwing leaves delivery pending. */
    public void deliver(String notificationNumber, Consumer<Notification> receiver) {
        if (receiver == null) { throw invalid("通知接收者不能为空"); }
        var notification = transactions.notification(number(notificationNumber));
        // The read transaction has ended. No simulator lock is held across the receiver's transaction.
        receiver.accept(notification);
        transactions.acknowledge(notification.notificationNumber());
    }

    private Payment open(Binding binding, boolean close) {
        try {
            try {
                return transactions.open(binding, close);
            } catch (DuplicateKeyException firstInsertLost) {
                // This proxy invocation has fully rolled back. A fresh transaction locks and re-reads the winner.
                return transactions.open(binding, close);
            }
        } catch (TransientDataAccessException lockFailure) {
            throw new BusinessException("SIMULATOR_RETRY_REQUIRED", "模拟渠道事务已回滚，请使用原支付编号与内容重试",
                    HttpStatus.CONFLICT);
        }
    }

    private Binding normalize(Binding binding) {
        if (binding == null || binding.amount() == null || binding.paymentExpiresAt() == null) {
            throw invalid("支付编号、商户订单号、金额和截止时间不能为空");
        }
        var paymentNumber = number(binding.paymentNumber());
        var merchantOrderNumber = number(binding.merchantOrderNumber());
        var amount = binding.amount();
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("模拟支付金额必须大于零且不超过 9999999999.99");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException extraDecimalPlaces) {
            throw invalid("模拟支付金额最多保留两位有效小数，不进行舍入");
        }
        var expiry = binding.paymentExpiresAt().truncatedTo(ChronoUnit.MICROS);
        try {
            int year = expiry.atOffset(ZoneOffset.UTC).getYear();
            if (year < 1000 || year > 9999) { throw invalid("支付截止时间超出数据库支持范围"); }
        } catch (DateTimeException unsupportedDate) {
            throw invalid("支付截止时间超出数据库支持范围");
        }
        return new Binding(paymentNumber, merchantOrderNumber, amount, expiry);
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
