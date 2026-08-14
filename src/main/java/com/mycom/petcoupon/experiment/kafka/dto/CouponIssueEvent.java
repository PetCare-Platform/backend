package com.mycom.petcoupon.experiment.kafka.dto;

import java.time.LocalDateTime;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;

import lombok.Builder;

@Builder
public record CouponIssueEvent(
        Long couponId,
        Long userId,
        String requestId,
        LocalDateTime issuedAt
) {

    public static CouponIssueEvent from(CouponIssueResponse response) {
        return CouponIssueEvent.builder()
                .couponId(response.couponId())
                .userId(response.userId())
                .requestId(response.requestId())
                .issuedAt(LocalDateTime.now())
                .build();
    }
}
