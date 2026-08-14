package com.mycom.petcoupon.experiment.coupon.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @Builder
    public Coupon(
            String name,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            Integer limitPerMember,
            String status) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (issueStartAt == null || issueEndAt == null || !issueEndAt.isAfter(issueStartAt)) {
            throw new IllegalArgumentException("issueEndAt must be after issueStartAt");
        }
        this.name = name;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
        this.limitPerMember = limitPerMember == null ? 1 : limitPerMember;
        this.status = status == null || status.isBlank() ? "OPEN" : status;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
