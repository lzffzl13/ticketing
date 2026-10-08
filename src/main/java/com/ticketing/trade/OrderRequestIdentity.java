package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.trade.TradeRequests.CreateOrder;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class OrderRequestIdentity {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private OrderRequestIdentity() { }

    static CreateOrder normalize(String key, CreateOrder request) {
        if (key == null || !REQUEST_KEY.matcher(key).matches()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "请求编号需为 1–64 位字母、数字、连字符或下划线",
                    HttpStatus.BAD_REQUEST);
        }
        if (request == null || request.sessionId() <= 0 || request.selectionMode() == null
                || request.seatIds() == null || request.seatIds().isEmpty()
                || request.seatIds().stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
            throw new BusinessException("INVALID_SELECTION", "场次和座位 ID 须为正数，座位集合不能为空或重复",
                    HttpStatus.BAD_REQUEST);
        }
        return new CreateOrder(request.sessionId(), request.selectionMode(), request.seatIds().stream().sorted().toList());
    }

    static String digest(CreateOrder normalized) {
        // Versioned, unambiguous format. Prices and client-supplied identities are not inputs.
        var canonical = "v1|" + normalized.sessionId() + "|" + normalized.selectionMode().name() + "|"
                + normalized.seatIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void requireSame(TradeModels.StoredOrder order, String digest) {
        if (!order.requestDigest().equals(digest)) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT", "请求编号已用于另一组购买内容，请为新购买使用新编号",
                    HttpStatus.CONFLICT);
        }
    }
}
