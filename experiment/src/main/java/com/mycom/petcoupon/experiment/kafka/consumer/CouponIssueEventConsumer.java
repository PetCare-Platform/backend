package com.mycom.petcoupon.experiment.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.kafka.constant.KafkaTopics;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CouponIssueEventConsumer {

    @KafkaListener(topics = KafkaTopics.COUPON_ISSUE_EVENT)
    public void consumeCouponIssueEvent(CouponIssueEvent event) {
        log.info("[CouponIssueEvent] 수신: couponId={}, userId={}, requestId={}",
                event.couponId(), event.userId(), event.requestId());
    }
}
