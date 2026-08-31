package com.mycom.petcoupon.experiment.coupon.repository;

import java.util.Optional;

import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    // 비관적 락: SELECT 시점에 row를 잠가서 다른 트랜잭션이 끝날 때까지 대기시킨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stock from CouponStock stock where stock.couponId = :couponId")
    Optional<CouponStock> findByIdWithPessimisticLock(@Param("couponId") Long couponId);

    // 낙관적 락: 잠그지 않고, 읽었던 version과 DB의 현재 version이 같을 때만 UPDATE가 적용된다.
    // 반환값이 0이면 그 사이 다른 트랜잭션이 먼저 값을 바꿨다는 뜻(compare-and-swap 실패) -> 재시도 필요.
    @Modifying(clearAutomatically = true)
    @Query("update CouponStock stock "
            + "set stock.issuedQuantity = stock.issuedQuantity + 1, "
            + "stock.remainingQuantity = stock.remainingQuantity - 1, "
            + "stock.version = stock.version + 1, "
            + "stock.updatedAt = CURRENT_TIMESTAMP "
            + "where stock.couponId = :couponId and stock.version = :version")
    int issueIfVersionMatches(@Param("couponId") Long couponId, @Param("version") Long version);

    // 조건부 UPDATE: 버전 없이, 재고가 남아있는지(remainingQuantity > 0)를 WHERE절에서 직접 체크한다.
    // 이 조건 자체가 원자적으로 평가되므로 재시도가 필요 없다 — 실패하면 그 시점에 정말 재고가 없는 것.
    @Modifying(clearAutomatically = true)
    @Query("update CouponStock stock "
            + "set stock.issuedQuantity = stock.issuedQuantity + 1, "
            + "stock.remainingQuantity = stock.remainingQuantity - 1, "
            + "stock.updatedAt = CURRENT_TIMESTAMP "
            + "where stock.couponId = :couponId and stock.remainingQuantity > 0")
    int issueIfStockAvailable(@Param("couponId") Long couponId);
}
