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

인프라 배선(설정 → Producer → Broker → Consumer → 장애 복구)뿐 아니라, Redis 재고 차감과 실제 발급 로직까지 연동되어 `strategy=KAFKA` 요청이 end-to-end로 동작합니다. 세부 구현 및 트러블슈팅 기록은 `Kafka_파이프라인_테스트_정리.md` 문서를 참고합니다.

```text
com.mycom.petcoupon.experiment
├── global/config/
│   └── KafkaConfig.java                    # KafkaAdmin, ProducerFactory, ConsumerFactory,
│                                            # ConcurrentKafkaListenerContainerFactory(재시도/에러핸들러) 빈
├── coupon/service/
│   └── KafkaCouponIssueServiceImpl.java    # CouponIssueService 구현체 (strategy=KAFKA)
└── kafka/
    ├── constant/KafkaTopics.java           # 토픽 이름 상수
    ├── dto/CouponIssueEvent.java           # 메시지 payload (record)
    ├── producer/CouponIssueEventProducer.java
    └── consumer/
        ├── CouponIssueEventConsumer.java   # 메시지 수신 및 CouponIssue 저장
        └── CouponIssueEventRecoverer.java  # 재시도 소진 후 최종 실패 처리 (log + Redis 실패 카운터 INCR + 재고 보상)
```

| 구성 요소 | 현재 동작 |
| --- | --- |
| `KafkaConfig` | 브로커 연결, 토픽 자동 생성, 재시도(`FixedBackOff(1000L, 2L)`) + 역직렬화 예외 처리까지 구성 완료. 토픽 파티션 수(3)에 맞춰 Consumer `concurrency=3` 설정 |
| `KafkaCouponIssueServiceImpl` | `RedisCouponStockService.decreaseStock()`으로 재고 차감(4가지 실패 코드 처리는 `RedisCouponIssueServiceImpl`과 동일) 후 **DB 저장 없이** `CouponIssueEventProducer.publishCouponIssueEvent()` 호출, 응답은 `CouponIssueResult.WAITING` |
| `CouponIssueEventProducer` | `publishCouponIssueEvent()` — `KafkaCouponIssueServiceImpl`에서 재고 차감 성공 직후 호출됨. 발행 자체가 실패하면(`whenComplete`의 실패 콜백) Consumer/Recoverer가 그 이벤트를 영영 볼 수 없으므로 여기서 직접 `restoreStock()`으로 재고 보상 |
| `CouponIssueEventConsumer` | 수신 시 `CouponIssue` 저장. 동일 `requestId` 재전달은 `existsByRequestId()`로 스킵. `saveAndFlush()`가 `DataIntegrityViolationException`을 던지면 `existsByRequestId()`로 재확인해서 — 이미 저장된 재전달이면 보상 없이 스킵, 아니면(예: 존재하지 않는 coupon/user FK 위반) `restoreStock()`으로 재고 보상 |
| `CouponIssueEventRecoverer` | 재시도(3회) 모두 실패 시 원인 로그 + Redis 실패 카운터 증가 + `RedisCouponStockService.restoreStock()`으로 차감된 재고 보상까지 수행 |

재고 보상은 이렇게 Producer/Consumer/Recoverer 세 경로에서 호출될 수 있어, `RedisLuaConfig.restoreStockScript()`는 `requestId` 예약 키가 남아있을 때만 복구하도록 멱등하게 구현돼 있습니다. 같은 요청이 여러 경로에서 중복 보상되더라도 재고가 실제 차감량보다 더 늘어나지 않습니다.

정리하면, **Redis 재고 차감 → 이벤트 발행 → Consumer 비동기 저장까지 정상 흐름과, DB 저장 실패(일시적 장애/제약 위반) 시 재고 보상까지 실제 인프라(docker-compose mysql/redis/kafka)에서 검증 완료**됐습니다. 자동화 테스트는 `KafkaCouponIssueServiceTest`(동시성 재고 제한, 중복 requestId, 중복 user, FK 위반 시 재고 보상)를 참고합니다.

---

## 3. Redis와의 관계

Kafka와 Redis는 현재 두 지점에서만 연결되어 있으며, 둘 다 쿠폰 발급 비즈니스 로직과는 무관합니다.

1. **패키지 위치 공유**: `RedisConfig`를 `KafkaConfig`와 같은 `global/config` 아래 두기로 협의
2. **장애 카운터**: `CouponIssueEventRecoverer`가 처리 최종 실패 시 `StringRedisTemplate`으로 실패 횟수만 기록 (모니터링 목적)

자세한 협의 내용은 `Kafka_Redis_협의사항.md`를 참고합니다.

재고 차감을 Redis에 맡기고 그 결과를 Kafka로 비동기 반영하는 조합은 `KafkaCouponIssueServiceImpl` → `CouponIssueEventConsumer`로 **구현되어 있습니다.**

---

## 4. 구현 완료 내역

`CouponIssueStrategy`에 `KAFKA`가 추가되어 `/experiment/coupons/{couponId}/issue?strategy=KAFKA` 요청이 정상 처리됩니다.

| 순서 | 작업 | 상태 |
| --- | --- | --- |
| 1 | `CouponIssueStrategy`에 `KAFKA` 추가 | ✅ |
| 2 | `KafkaCouponIssueServiceImpl` 구현: `RedisCouponStockService.decreaseStock()` → **DB 저장 없이** `CouponIssueEventProducer.publishCouponIssueEvent()` 호출, 응답은 `WAITING` | ✅ |
| 3 | `CouponIssueEventConsumer`에 실제 `CouponIssue` 저장 로직 추가 (재전달/unique 제약 충돌 처리, 실패 시 Redis 재고 보상(`restoreStock`) 포함) | ✅ |
| 4 | 응답 결과값 정리 — 동기 응답 시점엔 DB 반영 전이므로 `SUCCESS`가 아닌 `WAITING` 사용 | ✅ |

> 2번 항목에서 DB 저장은 `KafkaCouponIssueServiceImpl`이 직접 하지 않습니다. Consumer에서도 다시 저장하게 되면 이중 저장이 발생하므로, **DB 저장은 Consumer 한 곳에서만** 수행합니다.

3번 항목은 REDIS 전략(PR #23)에서 발견된 것과 동일한 문제(동시 중복 요청 시 재고 차감 후 DB insert가 unique 제약으로 실패하면서 재고가 보상되지 않는 문제)를 그대로 물려받을 수 있어 주의가 필요했습니다. 특히 구현 중 한 가지 함정이 있었는데, `CouponIssue`에는 unique 제약(`request_id`, `coupon_id`+`user_id`)뿐 아니라 `coupon_id`/`user_id`에 대한 **FK 제약**도 있고, MySQL에서는 두 위반이 똑같이 `DataIntegrityViolationException`으로 잡힙니다. "unique 위반 = 재전달이니 보상 불필요"로 단순하게 처리하면, 존재하지 않는 coupon/user로 인한 FK 위반(저장이 애초에 안 된 경우)까지 보상 없이 스킵되어 재고만 새는 버그가 생깁니다. 그래서 `DataIntegrityViolationException`을 잡은 뒤 `existsByRequestId()`로 한 번 더 확인해서, 실제로 저장이 끝난 재전달인지 아닌지를 구분해 보상 여부를 결정하도록 구현했습니다. 이 케이스는 `KafkaCouponIssueServiceTest.존재하지_않는_유저로_DB_저장이_실패하면_Redis_재고가_복구된다`로 검증됩니다.

---

## 5. 이 실험에서의 위치

Kafka 파이프라인은 동시성 실험 설계가 비교하는 5개 전략(재고 선점 시점의 동시성 제어 방식)과는 다른 층위의 문제, 즉 **DB 쓰기를 요청 처리와 분리하는 비동기화**를 다룹니다. 따라서:

- 5개 전략 비교의 k6 시나리오에는 포함하지 않습니다.
- 4절 구현이 완료되어 발급 로직 자체는 검증됐지만, "Redis 동기 응답 vs Redis+Kafka 비동기 응답"의 응답 지연시간·DB 부하 차이를 비교하는 별도 실험(k6)은 아직 진행하지 않았습니다 — 다음 단계로 남겨둡니다.
