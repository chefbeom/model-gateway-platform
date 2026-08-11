package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.Currency;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Small read-only query object kept separate from the existing RPM/token repository. */
@Repository
public class SpendCostQuery {
    @PersistenceContext
    private EntityManager entityManager;

    public BigDecimal sumForProjects(Collection<UUID> projectIds, Currency currency, Instant from) {
        if (projectIds == null || projectIds.isEmpty()) return BigDecimal.ZERO;
        return entityManager.createQuery("""
                select coalesce(sum(r.estimatedCost), 0)
                from LlmRequest r
                where r.projectId in :projectIds and r.costCurrency = :currency
                  and r.estimatedCost is not null and r.startedAt >= :from
                """, BigDecimal.class)
                .setParameter("projectIds", projectIds)
                .setParameter("currency", currency)
                .setParameter("from", from)
                .getSingleResult();
    }

    public BigDecimal sumForApiKey(UUID apiKeyId, Currency currency, Instant from) {
        return entityManager.createQuery("""
                select coalesce(sum(r.estimatedCost), 0)
                from LlmRequest r
                where r.apiKeyId = :apiKeyId and r.costCurrency = :currency
                  and r.estimatedCost is not null and r.startedAt >= :from
                """, BigDecimal.class)
                .setParameter("apiKeyId", apiKeyId)
                .setParameter("currency", currency)
                .setParameter("from", from)
                .getSingleResult();
    }
}
