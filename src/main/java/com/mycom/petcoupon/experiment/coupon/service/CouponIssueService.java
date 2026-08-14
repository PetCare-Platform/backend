package com.mycom.petcoupon.experiment.coupon.service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;

public interface CouponIssueService {

    CouponIssueResponse issue(Long couponId, CouponIssueRequest request);

    CouponIssueStrategy supports();
}
