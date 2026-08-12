package com.mycom.petcoupon.experiment.dto;

public record IssueResponse(
        Long couponId,
        Long userId,
        String strategy,
        boolean issued
) {
}
