package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpendQuotaReservationRepository extends JpaRepository<SpendQuotaReservation, UUID> {
    @Query("""
            select coalesce(sum(reservation.amount), 0)
            from SpendQuotaReservation reservation
            where reservation.quotaId = :quotaId
              and ((reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE
                    and reservation.expiresAt > :now)
                   or (reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED
                       and reservation.createdAt >= :periodStart and reservation.expiresAt > :now))
            """)
    BigDecimal sumActiveAmountByQuotaId(@Param("quotaId") UUID quotaId,
                                        @Param("periodStart") Instant periodStart,
                                        @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.RELEASED,
                reservation.settledAt = :now,
                reservation.reason = 'LEASE_EXPIRED'
            where reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE
              and reservation.expiresAt <= :now
            """)
    int reclaimExpiredActive(@Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.RELEASED,
                reservation.settledAt = :now,
                reservation.reason = 'LEASE_EXPIRED'
            where reservation.quotaId = :quotaId
              and reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE
              and reservation.expiresAt <= :now
            """)
    int reclaimExpiredActiveForQuota(@Param("quotaId") UUID quotaId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.SETTLED,
                reservation.settledAt = :now,
                reservation.reason = 'PERIOD_ROLLED_OVER'
            where reservation.quotaId = :quotaId
              and reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED
              and reservation.expiresAt <= :now
            """)
    int reclaimExpiredUnsettledForQuota(@Param("quotaId") UUID quotaId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = :toStatus,
                reservation.settledAt = :settledAt,
                reservation.reason = :reason
            where reservation.reservationKey = :reservationKey
              and reservation.status = :fromStatus
            """)
    int transitionByKey(@Param("reservationKey") UUID reservationKey,
                        @Param("fromStatus") ReservationStatus fromStatus,
                        @Param("toStatus") ReservationStatus toStatus,
                        @Param("settledAt") Instant settledAt,
                        @Param("reason") String reason);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.SETTLED,
                reservation.settledAt = :settledAt,
                reservation.reason = :reason
            where reservation.reservationKey = :reservationKey
              and reservation.status in (
                    com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE,
                    com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED)
            """)
    int settleByKey(@Param("reservationKey") UUID reservationKey,
                    @Param("settledAt") Instant settledAt,
                    @Param("reason") String reason);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED,
                reservation.expiresAt = :newExpiresAt,
                reservation.settledAt = null,
                reservation.reason = :reason
            where reservation.id = :id
              and reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE
            """)
    int holdUnsettledById(@Param("id") UUID id,
                          @Param("newExpiresAt") Instant newExpiresAt,
                          @Param("reason") String reason);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SpendQuotaReservation reservation
            set reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED,
                reservation.expiresAt = :newExpiresAt,
                reservation.settledAt = null,
                reservation.reason = :reason
            where reservation.reservationKey = :reservationKey
              and reservation.status = com.aiconnect.llmgateway.quota.SpendQuotaReservation.ReservationStatus.ACTIVE
            """)
    int holdUnsettledByKey(@Param("reservationKey") UUID reservationKey,
                           @Param("newExpiresAt") Instant newExpiresAt,
                           @Param("reason") String reason);

    List<SpendQuotaReservation> findByReservationKey(UUID reservationKey);

    List<SpendQuotaReservation> findByQuotaIdOrderByCreatedAtDesc(UUID quotaId);
}
