package com.ticketing.catalog;

import com.ticketing.common.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

import static com.ticketing.catalog.CatalogModels.*;

/** Write-side sales context. The caller keeps this session lock until its whole order commits. */
@Service
public class CatalogPurchaseService {
    private final CatalogMapper mapper;

    public CatalogPurchaseService(CatalogMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.MANDATORY)
    public SaleContext lockForPurchase(long sessionId) {
        var session = mapper.lockSession(sessionId);
        if (session == null) { throw new BusinessException("NOT_FOUND", "场次不存在", HttpStatus.NOT_FOUND); }
        var enabled = mapper.tiers(sessionId).stream().filter(PriceTier::enabled)
                .map(PriceTier::id).collect(Collectors.toSet());
        return new SaleContext(session, Set.copyOf(enabled));
    }

    public record SaleContext(Session session, Set<Long> enabledTierIds) { }
}
