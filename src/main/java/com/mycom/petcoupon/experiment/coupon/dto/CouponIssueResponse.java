package com.mycom.petcoupon.experiment.coupon.dto;

import lombok.Builder;

@Builder
public record CouponIssueResponse(
        Long couponId,
        Long userId,
        String requestId,
        CouponIssueResult result
) {

    public static CouponIssueResponse success(
            Long couponId,
            CouponIssueRequest request) {
        return CouponIssueResponse.builder()
                .couponId(couponId)
                .userId(request.userId())
                .requestId(request.requestId())
                .result(CouponIssueResult.SUCCESS)
                .build();
    }

    public static CouponIssueResponse failure(
            Long couponId,
            Long userId,
            String requestId,
            CouponIssueResult result) {
        return CouponIssueResponse.builder()
                .couponId(couponId)
                .userId(userId)
                .requestId(requestId)
                .result(result)
                .build();
    }
}
