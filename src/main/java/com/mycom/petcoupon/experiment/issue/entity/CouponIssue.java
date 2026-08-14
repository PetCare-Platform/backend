package com.mycom.petcoupon.experiment.issue.entity;

import java.time.LocalDateTime;

import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "coupon_issue",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_coupon_user",
                        columnNames = {"coupon_id", "user_id"}),
                @UniqueConstraint(
                        name = "uq_request_id",
                        columnNames = "request_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_issue_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false, updatable = false)
    private Coupon coupon;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "request_id", nullable = false, unique = true, length = 64, updatable = false)
    private String requestId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "fail_reason", length = 200)
    private String failReason;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // 공통 Entity에서는 낙관적 락을 활성화하지 않는다.
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public CouponIssue(Coupon coupon, User user, String requestId) {
        if (coupon == null || user == null) {
            throw new IllegalArgumentException("coupon and user are required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        this.coupon = coupon;
        this.user = user;
        this.requestId = requestId;
        this.status = "ISSUED";
        this.issuedAt = LocalDateTime.now();
        this.version = 0L;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (issuedAt == null) {
            issuedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getCouponId() {
        return coupon.getId();
    }

    public Long getUserId() {
        return user.getId();
    }
}
