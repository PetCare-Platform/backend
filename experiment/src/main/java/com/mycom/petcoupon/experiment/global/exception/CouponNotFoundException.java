package com.mycom.petcoupon.experiment.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CouponNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long couponId;

    public CouponNotFoundException(Long couponId) {
        super("Coupon stock was not found: couponId=" + couponId);
        this.couponId = couponId;
    }

    public Long getCouponId() {
        return couponId;
    }
}
