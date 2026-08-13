package com.mycom.petcoupon.experiment.coupon.repository;

import java.util.Optional;

import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stock from CouponStock stock where stock.couponId = :couponId")
    Optional<CouponStock> findByIdWithPessimisticLock(@Param("couponId") Long couponId);
}
