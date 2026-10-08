package com.ticketing.trade;

import com.ticketing.common.web.BusinessException;
import com.ticketing.trade.TradeRequests.CreateOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.ticketing.trade.TradeModels.*;

@Service
public class OrderService {
    private final OrderTransactionService writer;
    private final OrderQueryService queries;

    public OrderService(OrderTransactionService writer, OrderQueryService queries) {
        this.writer = writer;
        this.queries = queries;
    }

    // The facade stays outside the write transaction: a unique-key loser must roll back before re-reading.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult create(long customerId, String key, CreateOrder request) {
        var normalized = OrderRequestIdentity.normalize(key, request);
        var digest = OrderRequestIdentity.digest(normalized);
        var existing = queries.findByRequest(customerId, key);
        if (existing != null) { return replay(customerId, existing, digest); }
        try {
            return writer.create(customerId, key, normalized, digest);
        } catch (DuplicateKeyException duplicate) {
            // Different sessions don't share the session lock, but do share this customer's request key.
            var winner = queries.findByRequest(customerId, key);
            if (winner == null) { throw duplicate; }
            return replay(customerId, winner, digest);
        } catch (TransientDataAccessException lockFailure) {
            throw new BusinessException("ORDER_RETRY_REQUIRED", "本次事务已回滚，请使用原请求编号与内容重试",
                    HttpStatus.CONFLICT);
        }
    }

    public OrderDetails get(long customerId, long orderId) { return queries.get(customerId, orderId); }

    private CreationResult replay(long customerId, StoredOrder order, String digest) {
        OrderRequestIdentity.requireSame(order, digest);
        return new CreationResult(queries.get(customerId, order.id()), false);
    }
}
