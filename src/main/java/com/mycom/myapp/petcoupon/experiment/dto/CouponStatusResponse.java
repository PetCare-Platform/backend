package com.mycom.petcoupon.experiment.dto;

public record CouponStatusResponse(
        Long couponId,
        int totalQuantity,
        int dbRemainingQuantity,
        Long redisRemainingQuantity,
        long issueCount,
        boolean consistent
) {
}
