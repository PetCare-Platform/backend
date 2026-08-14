package com.mycom.petcoupon.experiment.coupon.service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponResponse;

public interface CouponExperimentService {

    CreateCouponResponse create(CreateCouponRequest request);

    CouponStatusResponse getStatus(Long couponId);

    CouponStatusResponse reset(Long couponId);
}
