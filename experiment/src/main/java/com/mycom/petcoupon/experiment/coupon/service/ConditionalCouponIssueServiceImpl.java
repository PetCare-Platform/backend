package com.mycom.petcoupon.experiment.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;

@Service
public class ConditionalCouponIssueServiceImpl implements CouponIssueService {

    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        // TODO: 조건부 UPDATE 전략 담당자가 발급 로직을 구현합니다.
        throw new UnsupportedOperationException("Conditional update strategy is not implemented yet");
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.CONDITIONAL;
    }
}
