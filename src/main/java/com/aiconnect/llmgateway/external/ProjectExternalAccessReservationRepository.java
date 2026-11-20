package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectExternalAccessReservationRepository extends JpaRepository<ProjectExternalAccessReservation, UUID> {
    @Query("""
            select coalesce(sum(r.amount), 0)
            from ProjectExternalAccessReservation r
            where r.projectId = :projectId
              and r.providerId = :providerId
              and r.currency = :currency
              and ((r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.ACTIVE
                    and r.expiresAt > :now)
                   or (r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED
                       and r.periodStart >= :periodStart and r.expiresAt > :now))
            """)
    BigDecimal sumReservedAmount(@Param("projectId") UUID projectId,
                                 @Param("providerId") UUID providerId,
                                 @Param("currency") Currency currency,
                                 @Param("periodStart") Instant periodStart,
                                 @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.RELEASED,
                r.settledAt = :now,
                r.reason = 'LEASE_EXPIRED'
            where r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.ACTIVE
              and r.expiresAt <= :now
            """)
    int reclaimExpired(@Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.RELEASED,
                r.settledAt = :now,
                r.reason = 'LEASE_EXPIRED'
            where r.projectId = :projectId
              and r.providerId = :providerId
              and r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.ACTIVE
              and r.expiresAt <= :now
            """)
    int reclaimExpiredForAccess(@Param("projectId") UUID projectId,
                                @Param("providerId") UUID providerId,
                                @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.SETTLED,
                r.settledAt = :now,
                r.reason = 'PERIOD_ROLLED_OVER'
            where r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED
              and r.expiresAt <= :now
            """)
    int reclaimExpiredUnsettled(@Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.SETTLED,
                r.settledAt = :now,
                r.reason = 'PERIOD_ROLLED_OVER'
            where r.projectId = :projectId
              and r.providerId = :providerId
              and r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED
              and r.expiresAt <= :now
            """)
    int reclaimExpiredUnsettledForAccess(@Param("projectId") UUID projectId,
                                         @Param("providerId") UUID providerId,
                                         @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = :toStatus,
                r.settledAt = :settledAt,
                r.reason = :reason
            where r.reservationKey = :reservationKey
              and r.status = :fromStatus
            """)
    int transitionByKey(@Param("reservationKey") UUID reservationKey,
                        @Param("fromStatus") ReservationStatus fromStatus,
                        @Param("toStatus") ReservationStatus toStatus,
                        @Param("settledAt") Instant settledAt,
                        @Param("reason") String reason);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectExternalAccessReservation r
            set r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED,
                r.expiresAt = :periodEnd,
                r.settledAt = null,
                r.reason = :reason
            where r.reservationKey = :reservationKey
              and r.status = com.aiconnect.llmgateway.external.ProjectExternalAccessReservation.ReservationStatus.ACTIVE
            """)
    int holdUnsettledByKey(@Param("reservationKey") UUID reservationKey,
                           @Param("periodEnd") Instant periodEnd,
                           @Param("reason") String reason);

    List<ProjectExternalAccessReservation> findByReservationKey(UUID reservationKey);

    List<ProjectExternalAccessReservation> findByProjectIdAndProviderIdOrderByCreatedAtDesc(UUID projectId, UUID providerId);
}
