package com.mycom.petcoupon.experiment.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventRecoverer implements ConsumerRecordRecoverer {

    private static final String FAIL_COUNT_KEY = "kafka:coupon-issue-event:fail-count";

    private final StringRedisTemplate redisTemplate;
    private final RedisCouponStockService redisCouponStockService;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("[CouponIssueEvent] 최종 처리 실패: partition={}, offset={}",
                record.partition(), record.offset(), exception);
        try {
            redisTemplate.opsForValue().increment(FAIL_COUNT_KEY);
        } catch (Exception redisException) {
            log.error("[CouponIssueEvent] 실패 카운터 기록 실패 (Redis 장애 추정): partition={}, offset={}",
                    record.partition(), record.offset(), redisException);
        }

        // 재시도까지 모두 소진된 뒤에도 DB 저장이 안 됐다는 뜻이므로, 차감됐던 재고를 되돌린다
        if (record.value() instanceof CouponIssueEvent event) {
            try {
                redisCouponStockService.restoreStock(event.couponId(), event.requestId(), event.userId());
                log.warn("[CouponIssueEvent] 재고 보상 완료: couponId={}, userId={}, requestId={}",
                        event.couponId(), event.userId(), event.requestId());
            } catch (Exception restoreException) {
                log.error("[CouponIssueEvent] 재고 보상 실패, 수동 확인 필요: couponId={}, userId={}, requestId={}",
                        event.couponId(), event.userId(), event.requestId(), restoreException);
            }
        } else {
            log.error("[CouponIssueEvent] 이벤트 역직렬화 실패로 재고 보상 불가, 수동 확인 필요: partition={}, offset={}",
                    record.partition(), record.offset());
        }
    }
}
