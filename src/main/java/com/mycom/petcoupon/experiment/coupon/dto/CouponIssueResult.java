package com.mycom.petcoupon.experiment.coupon.dto;

public enum CouponIssueResult {
    SUCCESS,
    SOLD_OUT,
    DUPLICATE_REQUEST,
    DUPLICATE_USER,
    COUPON_NOT_FOUND,
    INVALID_REQUEST,
    INTERNAL_ERROR,
    WAITING
}
