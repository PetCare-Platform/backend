package com.mycom.petcoupon.experiment.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;
import com.mycom.petcoupon.experiment.kafka.producer.CouponIssueEventProducer;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KafkaCouponIssueServiceImpl implements CouponIssueService {

    private final RedisCouponStockService redisCouponStockService;
    private final CouponIssueEventProducer couponIssueEventProducer;

    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {

        Long result = redisCouponStockService.decreaseStock(couponId, request.requestId(), request.userId());

        // Redis 재고 키 없음
        if (result == -1) {
            throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
        }

        // 재고 소진
        if (result == -2) {
            throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
        }

        // requestId 중복
        if (result == -3) {
            throw new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
        }

        // 동일 사용자 중복
        if (result == -4) {
            throw new GeneralException(ExperimentErrorCode.DUPLICATE_USER);
        }

        // DB 저장은 하지 않고 이벤트만 발행 — 실제 저장은 Consumer가 비동기로 수행
        CouponIssueResponse response = CouponIssueResponse.builder()
                .couponId(couponId)
                .userId(request.userId())
                .requestId(request.requestId())
                .result(CouponIssueResult.WAITING)
                .build();

        couponIssueEventProducer.publishCouponIssueEvent(CouponIssueEvent.from(response));

        return response;
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.KAFKA;
    }
}
