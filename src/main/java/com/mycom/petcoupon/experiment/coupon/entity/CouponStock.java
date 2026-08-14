package com.mycom.petcoupon.experiment.coupon.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "coupon_stock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponStock {

    @Id
    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "issued_quantity", nullable = false)
    private int issuedQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    // 다른 전략과 공유하는 일반 컬럼이며, 의도적으로 @Version을 사용X
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public CouponStock(Long couponId, int quantity) {
        if (couponId == null) {
            throw new IllegalArgumentException("couponId is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        this.couponId = couponId;
        this.totalQuantity = quantity;
        this.issuedQuantity = 0;
        this.remainingQuantity = quantity;
        this.version = 0L;
    }

    public void issue() {
        if (remainingQuantity <= 0) {
            throw new IllegalStateException("Coupon stock is exhausted");
        }
        issuedQuantity++;
        remainingQuantity--;
    }

    public void reset() {
        issuedQuantity = 0;
        remainingQuantity = totalQuantity;
        version = 0L;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}
