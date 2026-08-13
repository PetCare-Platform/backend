package com.mycom.petcoupon.experiment.coupon.type;

public enum CouponIssueStrategy {
    DIRECT,
    PESSIMISTIC,
    OPTIMISTIC,
    CONDITIONAL,
    REDIS
}
