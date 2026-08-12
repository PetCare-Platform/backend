package com.mycom.petcoupon.experiment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CouponNotFoundException extends RuntimeException {

    public CouponNotFoundException(Long couponId) {
        super("쿠폰을 찾을 수 없습니다. couponId=" + couponId);
    }
}
