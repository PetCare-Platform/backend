package com.mycom.petcoupon.experiment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "coupon_stock")
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

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CouponStock() {
    }

    public CouponStock(Long couponId, int quantity) {
        if (couponId == null) {
            throw new IllegalArgumentException("couponId는 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("쿠폰 수량은 1 이상이어야 합니다.");
        }
        this.couponId = couponId;
        this.totalQuantity = quantity;
        this.issuedQuantity = 0;
        this.remainingQuantity = quantity;
        this.version = 0L;
        this.updatedAt = LocalDateTime.now();
    }

    public void decrease() {
        if (remainingQuantity <= 0) {
            throw new IllegalStateException("쿠폰 재고가 없습니다.");
        }
        remainingQuantity--;
        issuedQuantity++;
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

    public Long getCouponId() {
        return couponId;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedQuantity() {
        return issuedQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
