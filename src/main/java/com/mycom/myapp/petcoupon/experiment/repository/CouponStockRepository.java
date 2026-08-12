package com.mycom.petcoupon.experiment.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.experiment.entity.CouponStock;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stock from CouponStock stock where stock.couponId = :couponId")
    Optional<CouponStock> findByIdForUpdate(@Param("couponId") Long couponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CouponStock stock
               set stock.remainingQuantity = stock.remainingQuantity - 1,
                   stock.issuedQuantity = stock.issuedQuantity + 1,
                   stock.updatedAt = current_timestamp,
                   stock.version = stock.version + 1
             where stock.couponId = :couponId
               and stock.version = :version
               and stock.remainingQuantity > 0
            """)
    int decreaseWithVersion(
            @Param("couponId") Long couponId,
            @Param("version") long version);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CouponStock stock
               set stock.remainingQuantity = stock.remainingQuantity - 1,
                   stock.issuedQuantity = stock.issuedQuantity + 1,
                   stock.updatedAt = current_timestamp
             where stock.couponId = :couponId
               and stock.remainingQuantity > 0
            """)
    int decreaseIfAvailable(@Param("couponId") Long couponId);
}
