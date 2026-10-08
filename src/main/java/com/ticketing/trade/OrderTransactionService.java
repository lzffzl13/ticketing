package com.ticketing.trade;

import com.ticketing.catalog.CatalogModels.SalesStatus;
import com.ticketing.catalog.CatalogPurchaseService;
import com.ticketing.common.persistence.GeneratedKey;
import com.ticketing.common.web.BusinessException;
import com.ticketing.inventory.InventoryHoldService;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.trade.TradeRequests.CreateOrder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.ticketing.trade.TradeModels.*;

/** One local transaction covers the order, snapshots, whole hold and every owned seat. */
@Service
public class OrderTransactionService {
    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999.99");
    private final CatalogPurchaseService catalog;
    private final InventoryHoldService inventory;
    private final TradeMapper mapper;
    private final OrderQueryService queries;
    private final Clock clock;

    public OrderTransactionService(CatalogPurchaseService catalog, InventoryHoldService inventory,
                                   TradeMapper mapper, OrderQueryService queries, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.mapper = mapper;
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreationResult create(long customerId, String key, CreateOrder request, String digest) {
        // READ COMMITTED prevents a pre-lock lookup from leaving us with an old inventory snapshot.
        var sale = catalog.lockForPurchase(request.sessionId());
        var existing = mapper.byRequest(customerId, key);
        if (existing != null) {
            OrderRequestIdentity.requireSame(existing, digest);
            return new CreationResult(queries.get(customerId, existing.id()), false);
        }
        var session = sale.session();
        if (session.salesStatus() != SalesStatus.ON_SALE) {
            throw conflict("SESSION_NOT_ON_SALE", "场次尚未开售或已暂停销售");
        }
        // Time is read after waiting for the lock, once, and at database precision.
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var expiresAt = at.plus(PAYMENT_WINDOW);
        if (expiresAt.isAfter(session.admissionCloseAt())) {
            throw conflict("ORDER_SALES_CLOSED", "距离入场截止不足 15 分钟，不能创建新订单");
        }
        var selected = inventory.validateSelection(session.id(), request.seatIds(), sale.enabledTierIds(),
                request.selectionMode() == SelectionMode.RECOMMENDED, session.forbidNewOrphan());
        var total = selected.stream().map(SessionSeat::priceSnapshot).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 || total.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new BusinessException("ORDER_AMOUNT_TOO_LARGE", "订单总金额超出支持范围", HttpStatus.BAD_REQUEST);
        }
        var order = new StoredOrder(null, UUID.randomUUID().toString(), customerId, session.id(),
                request.selectionMode(), OrderStatus.PENDING_PAYMENT, total, expiresAt, key, digest, at, at);
        var generated = new GeneratedKey();
        mapper.insertOrder(order, generated);
        inventory.createHold(generated.getId(), session.id(), selected, at, expiresAt);
        for (var seat : selected) { mapper.insertItem(generated.getId(), session.id(), seat, at, new GeneratedKey()); }
        return new CreationResult(queries.get(customerId, generated.getId()), true);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
