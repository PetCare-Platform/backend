package com.mycom.petcoupon.experiment.kafka.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.kafka.constant.KafkaTopics;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventProducer {

    private final KafkaTemplate<String, CouponIssueEvent> kafkaTemplate;
    private final RedisCouponStockService redisCouponStockService;

    public void publishCouponIssueEvent(CouponIssueEvent event) {
        try {
            kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, event.requestId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[CouponIssueEvent] 발행 실패: requestId={}", event.requestId(), ex);
                            restoreStock(event);
                        } else {
                            log.info("[CouponIssueEvent] 발행 성공: requestId={}", event.requestId());
                        }
                    });
        } catch (RuntimeException e) {
            // send()가 Future를 반환하기 전에 동기 예외를 던지면 whenComplete 콜백이 아예 등록되지 않으므로
            // 여기서 직접 보상하고, 호출자가 WAITING 응답을 내려보내지 않도록 예외를 그대로 전파
            log.error("[CouponIssueEvent] 발행 시도 자체가 동기 예외로 실패: requestId={}", event.requestId(), e);
            restoreStock(event);
            throw e;
        }
    }

    // 메시지가 토픽에 들어가지 못했으므로 Consumer/Recoverer가 절대 이 이벤트를 볼 수 없음 → 여기서 직접 보상
    private void restoreStock(CouponIssueEvent event) {
        try {
            redisCouponStockService.restoreStock(event.couponId(), event.requestId(), event.userId());
            log.warn("[CouponIssueEvent] 발행 실패로 인한 재고 보상 완료: couponId={}, userId={}, requestId={}",
                    event.couponId(), event.userId(), event.requestId());
        } catch (Exception restoreException) {
            log.error("[CouponIssueEvent] 발행 실패 후 재고 보상 실패, 수동 확인 필요: couponId={}, userId={}, requestId={}",
                    event.couponId(), event.userId(), event.requestId(), restoreException);
        }
    }
}
