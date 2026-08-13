package com.mycom.petcoupon.experiment.kafka.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.kafka.constant.KafkaTopics;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventProducer {

    private final KafkaTemplate<String, CouponIssueEvent> kafkaTemplate;

    public void publishCouponIssueEvent(CouponIssueEvent event) {
        kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, event.requestId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[CouponIssueEvent] 발행 실패: requestId={}", event.requestId(), ex);
                    } else {
                        log.info("[CouponIssueEvent] 발행 성공: requestId={}", event.requestId());
                    }
                });
    }
}
