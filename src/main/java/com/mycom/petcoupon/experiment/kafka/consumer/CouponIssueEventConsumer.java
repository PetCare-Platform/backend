package com.mycom.petcoupon.experiment.kafka.consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.kafka.constant.KafkaTopics;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventConsumer {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final RedisCouponStockService redisCouponStockService;

    @KafkaListener(topics = KafkaTopics.COUPON_ISSUE_EVENT)
    public void consumeCouponIssueEvent(CouponIssueEvent event) {
        log.info("[CouponIssueEvent] 수신: couponId={}, userId={}, requestId={}",
                event.couponId(), event.userId(), event.requestId());

        // 재전달된 메시지 (이전 처리에서 저장까지는 성공했으나 오프셋 커밋이 안 된 경우) — 재고 보상 없이 스킵
        if (couponIssueRepository.existsByRequestId(event.requestId())) {
            log.warn("[CouponIssueEvent] 이미 저장된 requestId 재수신, 스킵: requestId={}", event.requestId());
            return;
        }

        try {
            couponIssueRepository.saveAndFlush(
                    CouponIssue.builder()
                            .coupon(couponRepository.getReferenceById(event.couponId()))
                            .user(userRepository.getReferenceById(event.userId()))
                            .requestId(event.requestId())
                            .build()
            );
            log.info("[CouponIssueEvent] 저장 완료: requestId={}", event.requestId());
        } catch (DataIntegrityViolationException e) {
            if (couponIssueRepository.existsByRequestId(event.requestId())) {
                // 이 requestId로 이미 저장이 끝난 상태 (재전달) — 재고는 정상 소진된 것이므로 보상하지 않음
                log.warn("[CouponIssueEvent] 재전달로 인한 저장 스킵 (이미 처리된 것으로 확인): requestId={}",
                        event.requestId(), e);
            } else {
                // requestId 충돌이 아닌 다른 제약 위반 (예: 존재하지 않는 coupon/user FK) — 저장은 안 됐으므로 재고 보상 필요
                redisCouponStockService.restoreStock(event.couponId(), event.requestId(), event.userId());
                log.error("[CouponIssueEvent] 제약 위반으로 저장 실패, 재고 보상 완료: requestId={}",
                        event.requestId(), e);
            }
        }
        // 그 외 예외는 그대로 던져서 DefaultErrorHandler의 재시도(FixedBackOff) 대상이 되도록 함
    }
}
