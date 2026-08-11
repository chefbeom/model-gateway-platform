package com.aiconnect.llmgateway.quota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface SpendQuotaReservationRepository extends JpaRepository<SpendQuotaReservation, UUID> {
    @Query("select coalesce(sum(reservation.amount), 0) from SpendQuotaReservation reservation " +
            "where reservation.quotaId = :quotaId and reservation.expiresAt > :now")
    BigDecimal sumActiveAmountByQuotaId(@Param("quotaId") UUID quotaId, @Param("now") Instant now);

    @Modifying
    @Query("delete from SpendQuotaReservation reservation where reservation.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from SpendQuotaReservation reservation where reservation.reservationKey = :reservationKey")
    int deleteByReservationKey(@Param("reservationKey") UUID reservationKey);
}
