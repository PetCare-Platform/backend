package com.mycom.petcoupon.experiment.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventRecoverer implements ConsumerRecordRecoverer {

    private static final String FAIL_COUNT_KEY = "kafka:coupon-issue-event:fail-count";

    private final StringRedisTemplate redisTemplate;

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
    }
}
