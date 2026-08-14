package com.mycom.petcoupon.experiment.coupon.dto;

import lombok.Builder;

@Builder
public record CouponStatusResponse(
        Long couponId,
        int totalQuantity,
        int dbRemainingQuantity,
        Long redisRemainingQuantity,
        long issueCount,
        boolean consistent
) {
}
