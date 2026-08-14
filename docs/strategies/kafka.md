# KAFKA (비동기 발급 파이프라인)

> 이 문서는 `CouponIssueStrategy`로 비교하는 5개 전략(DIRECT/PESSIMISTIC/OPTIMISTIC/CONDITIONAL/REDIS)과는 별개로 진행 중인 **Kafka 기반 비동기 처리**의 현재 구현 상태와 남은 작업을 정리한 문서입니다.
>
> 동시성 실험 설계 문서에서 Kafka 비동기 처리는 이번 5개 전략 비교의 범위 밖으로 명시되어 있으며, Redis 전략 이후 후속 설계로 진행합니다.

---

## 1. 목적

Redis 전략(`REDIS`)은 Lua Script로 재고 차감을 원자적으로 처리하지만, 재고 소진 여부 확인과 실제 발급 이력(`CouponIssue`) 저장은 요청을 받은 스레드가 그 자리에서 동기적으로 수행합니다.

Kafka 파이프라인의 목적은 이 중 **DB 저장을 요청 처리 흐름에서 분리**하는 것입니다.

```text
(기존 REDIS 전략)
요청 → Redis 재고 차감 → DB 저장 → 응답

(Kafka 적용 목표)
요청 → Redis 재고 차감 → 이벤트 발행 → 응답
                              ↓ (비동기)
                         Consumer가 DB 저장
```

요청을 처리하는 스레드는 Redis 차감과 이벤트 발행까지만 책임지고, DB 쓰기 부하는 Consumer 쪽으로 넘겨 응답 속도와 DB 부하를 분리해서 관찰하는 것이 목표입니다.

---

## 2. 현재 구현 상태

인프라 배선(설정 → Producer → Broker → Consumer → 장애 복구)까지는 검증이 끝났습니다. 세부 구현 및 트러블슈팅 기록은 `Kafka_파이프라인_테스트_정리.md` 문서를 참고합니다.

```text
com.mycom.petcoupon.experiment
├── global/config/
│   └── KafkaConfig.java                    # KafkaAdmin, ProducerFactory, ConsumerFactory,
│                                            # ConcurrentKafkaListenerContainerFactory(재시도/에러핸들러) 빈
└── kafka/
    ├── constant/KafkaTopics.java           # 토픽 이름 상수
    ├── dto/CouponIssueEvent.java           # 메시지 payload (record)
    ├── producer/CouponIssueEventProducer.java
    └── consumer/
        ├── CouponIssueEventConsumer.java   # 메시지 수신 처리
        └── CouponIssueEventRecoverer.java  # 재시도 소진 후 최종 실패 처리 (log + Redis 실패 카운터 INCR)
```

| 구성 요소 | 현재 동작 |
| --- | --- |
| `KafkaConfig` | 브로커 연결, 토픽 자동 생성, 재시도(`FixedBackOff(1000L, 2L)`) + 역직렬화 예외 처리까지 구성 완료 |
| `CouponIssueEventProducer` | `publishCouponIssueEvent()` 구현 완료. **다만 실제 발급 흐름 어디에서도 호출되지 않음** — 콘솔 프로듀서로 수동 발행만 검증 |
| `CouponIssueEventConsumer` | 메시지를 수신하면 `log.info`로 내용만 기록. **재고 차감이나 DB 저장 로직 없음** |
| `CouponIssueEventRecoverer` | 최종 실패 시 원인 로그 + Redis 실패 카운터(`kafka:coupon-issue-event:fail-count`) 증가까지 구현 완료 |

정리하면, **"메시지가 안전하게 오가는 것"까지는 검증되었고 "메시지로 무엇을 할지"는 아직 비어 있는 상태**입니다.

---

## 3. Redis와의 관계

Kafka와 Redis는 현재 두 지점에서만 연결되어 있으며, 둘 다 쿠폰 발급 비즈니스 로직과는 무관합니다.

1. **패키지 위치 공유**: `RedisConfig`를 `KafkaConfig`와 같은 `global/config` 아래 두기로 협의
2. **장애 카운터**: `CouponIssueEventRecoverer`가 처리 최종 실패 시 `StringRedisTemplate`으로 실패 횟수만 기록 (모니터링 목적)

자세한 협의 내용은 `Kafka_Redis_협의사항.md`를 참고합니다.

재고 차감을 Redis에 맡기고 그 결과를 Kafka로 비동기 반영하는 조합은 **아직 구현되지 않았습니다.**

---

## 4. 남은 작업

`CouponIssueStrategy`에 `KAFKA`가 정의되어 있지 않아, 현재 `/experiment/coupons/{couponId}/issue?strategy=KAFKA` 요청은 처리할 수 없습니다.

| 순서 | 작업 | 비고 |
| --- | --- | --- |
| 1 | `CouponIssueStrategy`에 `KAFKA` 추가 | - |
| 2 | `KafkaCouponIssueServiceImpl` 구현: `rejectDuplicate()` → `RedisCouponStockService.decreaseStock()` → **DB 저장 없이** `CouponIssueEventProducer.publishCouponIssueEvent()` 호출 | Redis/Kafka 협의 필요 |
| 3 | `CouponIssueEventConsumer`에 실제 `CouponIssue` 저장 로직 추가 (unique 제약 충돌 처리, 실패 시 Redis 재고 보상(`increaseStock`) 포함) | Kafka 담당 |
| 4 | 응답 결과값 정리 (동기 응답 시점엔 DB 반영 전이므로 `SUCCESS`가 아닌 별도 결과 필요 여부 논의) | - |

> 2번 항목에서 DB 저장을 Redis 쪽 구현체가 직접 하면 안 됩니다. Consumer에서도 다시 저장하게 되면 이중 저장이 발생합니다. **DB 저장은 Consumer 한 곳에서만** 수행합니다.

3번 항목은 REDIS 전략(PR #23)에서 발견된 것과 동일한 문제(동시 중복 요청 시 재고 차감 후 DB insert가 unique 제약으로 실패하면서 재고가 보상되지 않는 문제)를 그대로 물려받을 수 있어, Consumer 구현 시 `DataIntegrityViolationException` 처리와 `increaseStock()` 보상 로직을 함께 고려해야 합니다.

---

## 5. 이 실험에서의 위치

Kafka 파이프라인은 동시성 실험 설계가 비교하는 5개 전략(재고 선점 시점의 동시성 제어 방식)과는 다른 층위의 문제, 즉 **DB 쓰기를 요청 처리와 분리하는 비동기화**를 다룹니다. 따라서:

- 5개 전략 비교의 k6 시나리오에는 포함하지 않습니다.
- Redis 전략 구현 및 검증이 끝난 뒤, 별도 실험으로 "Redis 동기 응답 vs Redis+Kafka 비동기 응답"의 응답 지연시간·DB 부하 차이를 비교하는 것을 목표로 합니다.
- 위 4절이 완료되기 전까지 이 문서의 "구현 상태"는 파이프라인 뼈대로 한정됩니다.
