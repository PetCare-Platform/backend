package com.mycom.petcoupon.experiment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "issue_start_at", nullable = false)
    private LocalDateTime issueStartAt;

    @Column(name = "issue_end_at", nullable = false)
    private LocalDateTime issueEndAt;

    @Column(name = "limit_per_member", nullable = false)
    private int limitPerMember;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Coupon() {
    }

    public Coupon(String name) {
        LocalDateTime now = LocalDateTime.now();
        this.name = name;
        this.issueStartAt = now;
        this.issueEndAt = now.plusDays(1);
        this.limitPerMember = 1;
        this.status = "OPEN";
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }
}
