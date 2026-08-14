# 동시성 실험 설계

> 이 문서는 쿠폰 발급 동시성 실험의 기준을 정리한 초안입니다.  
> 구현 및 실험 진행 과정에서 세부 조건과 측정 항목은 변경될 수 있습니다.

## 1. 실험 목적

선착순 쿠폰 발급 상황에서는 짧은 시간에 동일한 쿠폰 재고에 다수의 요청이 동시에 접근합니다.

이 실험은 다음 세 단계로 진행합니다.

1. `DIRECT`, `PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`, `REDIS`의 재고 차감 성능과 정합성을 비교합니다.
2. `REDIS` 단독 처리와 `REDIS_KAFKA` 비동기 처리의 응답 성능 및 최종 정합성을 비교합니다.
3. 최종 적용 후보인 `REDIS_KAFKA` 방식으로 재고 10,000개, 요청 20,000건 요구사항을 검증합니다.

각 실험에서는 다음 항목을 동일한 조건에서 비교합니다.

- 쿠폰 재고 정합성
- 초과 발급 및 중복 발급 여부
- 요청 처리량
- 응답 지연시간
- 높은 경합 상황에서의 동작 특성

실험 결과는 특정 방식의 절대적인 우열을 결정하기보다, 각 방식이 어떤 조건에서 어떤 특성을 보이는지 확인하는 데 사용합니다.


## 2. 비교 대상

동일한 쿠폰 발급 기능을 다음 전략으로 구현하여 비교합니다.

| Strategy | 설명 |
| --- | --- |
| `DIRECT` | 별도의 동시성 제어 없이 DB 조회 및 수정 |
| `PESSIMISTIC` | DB 비관적 락을 이용한 재고 제어 |
| `OPTIMISTIC` | version 기반 CAS 방식 |
| `CONDITIONAL` | 재고 조건을 포함한 원자적 UPDATE |
| `REDIS` | Redis Lua 원자 연산으로 재고를 차감한 뒤 같은 요청에서 DB에 발급 이력을 저장 |
| `REDIS_KAFKA` | Redis Lua 원자 연산으로 재고를 선점하고 Kafka Consumer가 DB 발급 이력을 비동기로 저장 |

각 전략의 구현 원리와 세부 코드 구조는 별도 문서에서 다룹니다.

- [실험 공통 코드 구조](./common-code.md)
- [동시성 실험 결과](./experiment-results.md)
- [DIRECT](./strategies/direct.md)
- [PESSIMISTIC](./strategies/pessimistic.md)
- [OPTIMISTIC](./strategies/optimistic.md)
- [CONDITIONAL](./strategies/conditional.md)
- [REDIS](./strategies/redis.md)

현재 공통 전략 API와 k6 스크립트는 `REDIS`까지 구현되어 있습니다. `REDIS_KAFKA`는 구현 완료 후 같은 실험 규격에 연결합니다.

## 3. 비교 원칙

전략별 결과를 비교할 때 동시성 제어 방식 외의 조건이 결과에 영향을 주지 않도록 가능한 한 동일한 환경을 유지합니다.

- **실험 A — 재고 차감 전략 비교:** DB 4개 전략과 Redis 단독 전략을 비교합니다.
- **실험 B — 처리 파이프라인 비교:** Redis 단독과 Redis/Kafka를 비교합니다.
- **최종 검증:** 실험 결과와 최종 설계를 반영한 Redis/Kafka 방식을 10,000/20,000 규모로 검증합니다.

### 동일하게 유지할 항목

- 동일한 애플리케이션 실행 환경
- 동일한 MySQL 인스턴스 및 스키마
- 동일한 애플리케이션 설정
- 동일한 Connection Pool 설정
- 동일한 초기 쿠폰 재고
- 동일한 테스트 사용자 수
- 동일한 총 요청 수
- 동일한 VU 또는 동시 요청 조건
- 동일한 요청 DTO
- 동일한 성공/실패 판정 기준
- 동일한 부하 테스트 도구 및 실행 머신

실험 A에서는 재고 차감 방식만 독립 변수로 변경합니다. 실험 B에서는 Redis 재고 선점 로직은 동일하게 유지하고, DB 발급 이력 저장을 동기 호출로 처리하는지 Kafka Consumer가 비동기로 처리하는지만 변경합니다.


## 4. 실험 데이터

기본 실험 데이터는 다음과 같이 구성합니다.

| 항목 | 기본값 |
| --- | ---: |
| 사용자 수 | 200명 |
| 쿠폰별 초기 재고 | 100개 |
| 총 요청 수 | 200건 |
| 사용자별 요청 | 1건 |
| 쿠폰별 발급 제한 | 1인 1장 |

각 요청에는 실제 DB에 존재하는 서로 다른 `userId`를 사용합니다.

`requestId` 역시 요청마다 고유하게 생성하여 중복 요청 검증이 기본 동시성 실험에 영향을 주지 않도록 합니다.

요청 예시:

```json
{
  "userId": 1,
  "requestId": "DIRECT-run1-user1"
}
```


## 5. 전략별 쿠폰 분리

각 전략은 별도의 쿠폰 데이터를 사용합니다.

예시:

| couponId | 용도 |
| ---: | --- |
| 1 | PESSIMISTIC |
| 2 | DIRECT |
| 3 | OPTIMISTIC |
| 4 | CONDITIONAL |
| 5 | REDIS |
| 별도 지정 | REDIS_KAFKA |

전략별로 쿠폰을 분리하는 이유는 한 전략에서 변경한 재고 또는 version 값이 다른 전략의 실험에 영향을 주는 것을 방지하기 위해서입니다.

실제 couponId는 초기 데이터 또는 실험 환경에 따라 변경할 수 있습니다.

재고 규모가 다른 각 단계에서는 쿠폰 생성 API로 해당 수량의 쿠폰을 새로 생성하고, 응답으로 받은 `couponId`를 k6에 전달합니다.

```http
POST /experiment/coupons
Content-Type: application/json

{
  "quantity": 500
}
```

Reset API는 재고를 쿠폰의 기존 `total_quantity`로 복원할 뿐 총재고 수량을 변경하지 않으므로, 재고 규모가 달라지면 새 쿠폰을 생성합니다.


## 6. 테스트 시나리오

### 6.1 단건 정상 발급

| 구분 | 내용 |
| --- | --- |
| 목적 | API와 DB 저장이 기본적으로 정상 동작하는지 확인 |
| 사전 조건 | 재고 10장, 발급 이력 0건, 사용자 1명 |
| 실행 | 사용자 1명이 쿠폰 신청 API를 1회 호출 |
| 기대 결과 | 성공 1건, 발급 이력 1건, issued_quantity 1, remaining_quantity 9 |

### 6.2 재고 이내 동시 요청

| 구분 | 내용 |
| --- | --- |
| 목적 | 재고가 충분할 때 모든 요청이 정상 처리되는지 확인 |
| 사전 조건 | 재고 100장, 서로 다른 사용자 50명, 고유 requestId 50개 |
| 실행 | 50명이 동시에 신청 |
| 기대 결과 | 성공 50건, 매진 0건, 발급 이력 50건, 잔여 재고 50 |

### 6.3 재고와 동일한 동시 요청

| 구분 | 내용 |
| --- | --- |
| 목적 | 재고 경계에서 정확히 소진되는지 확인 |
| 사전 조건 | 재고 100장, 서로 다른 사용자 100명 |
| 실행 | 100명이 동시에 신청 |
| 기대 결과 | 성공 100건, 발급 이력 100건, 잔여 재고 0, 초과 발급 0건 |

### 6.4 재고 초과 동시 요청 — 핵심

| 구분 | 내용 |
| --- | --- |
| 목적 | 재고보다 많은 요청에서도 초과 발급을 방지하는지 확인 |
| 사전 조건 | 재고 100장, 서로 다른 사용자 200명, 고유 requestId 200개 |
| 실행 | 200명이 동시에 신청 |
| 기대 결과 | 정합성 보장 전략은 성공 100건, 매진 100건, 발급 이력 100건, 잔여 재고 0 |

`DIRECT` 전략은 동시성 제어가 없는 비교 기준이므로 성공 건수를 미리 단정하지 않습니다. 
초과 발급, Lost Update, 재고와 발급 이력의 불일치 발생 여부를 관찰하고 다른 전략 도입의 근거로 사용합니다.


## 7. 중복·멱등성 시나리오

중복과 멱등성은 재고 차감 전략의 순수 성능 비교와 분리하여 실행합니다.

| 시나리오 | 입력 | 기대 결과 |
| --- | --- | --- |
| 동일 사용자 반복 요청 | 동일 userId, 서로 다른 requestId로 10회 동시 신청 | 성공 1건, DUPLICATE_USER 9건, 재고 감소 1, 발급 이력 1건 |
| 동일 요청 재전송 | 동일 userId와 동일 requestId로 10회 요청 | 성공 1건, DUPLICATE_REQUEST 9건, 재고 감소 1, 발급 이력 1건 |

현재 실험 API는 동일한 `requestId`가 재전송되면 최초 응답을 다시 반환하지 않고 `DUPLICATE_REQUEST`로 차단합니다. 
따라서 이번 실험에서는 완전한 응답 멱등성이 아니라 동일 요청이 재고와 발급 이력에 두 번 이상 반영되지 않는지를 검증합니다.

최초 처리 결과를 재반환하는 완전한 멱등성은 최종 시스템 구현 후 별도로 검증합니다.


## 8. 단계별 성능 비교

### 8.1 실험 A — 재고 차감 전략 비교

`DIRECT`, `PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`, `REDIS`를 다음 단계로 비교합니다.

| 단계 | 재고 | 요청 수 | VUS | 목적 |
| --- | ---: | ---: | ---: | --- |
| 스모크 | 10 | 20 | 20 | 환경과 k6 스크립트 정상 동작 확인 |
| 기본 | 100 | 200 | 200 | 동시성 및 정합성 확인 |
| 중간 | 500 | 1,000 | 1,000 | 처리량과 응답시간 변화 확인 |
| 공통 최대 | 1,000 | 2,000 | 2,000 | 다섯 전략의 처리량·지연시간·정합성 최종 비교 |

### 8.2 실험 B — Redis 처리 파이프라인 비교

`REDIS`와 `REDIS_KAFKA`를 실험 A와 동일한 네 단계로 비교합니다. Redis 재고 선점 방식과 테스트 데이터는 동일하게 유지합니다.

| 단계 | 재고 | 요청 수 | VUS | 목적 |
| --- | ---: | ---: | ---: | --- |
| 스모크 | 10 | 20 | 20 | Kafka 발행·소비·DB 저장 흐름 확인 |
| 기본 | 100 | 200 | 200 | 동기/비동기 처리의 응답시간과 정합성 비교 |
| 중간 | 500 | 1,000 | 1,000 | 처리량 및 비동기 적체 변화 확인 |
| 공통 최대 | 1,000 | 2,000 | 2,000 | Redis 단독과 Redis/Kafka 최종 비교 |

### 8.3 최종 요구사항 검증

| 대상 | 재고 | 요청 수 | VUS | 목적 |
| --- | ---: | ---: | ---: | --- |
| `REDIS_KAFKA` | 10,000 | 20,000 | 20,000 | 초과 발급 0건, 1인 1매, 최종 정합성 및 대규모 처리 성능 검증 |

각 단계에서 `VUS`와 `ITERATIONS`를 전체 요청 수와 동일하게 설정하여, 각 VU가 한 번씩 요청을 보내도록 합니다. 최종 20,000 VUS 실행 전에는 부하 발생기의 CPU·메모리·네트워크를 확인하여 부하 발생기가 먼저 병목이 되지 않도록 합니다.

비교 실험은 동일 조건에서 최소 3회 실행하고 TPS, 평균, p95, p99, 성공·매진·오류 건수와 정합성을 함께 기록합니다. p95는 전체 요청의 95%가 해당 시간 이내에 응답했다는 의미이며, 이번 실험에서는 우선 전략 간 비교 지표로 사용합니다. 고정 합격 기준은 실제 측정 결과를 확인한 뒤 확정합니다.


## 9. 측정 항목

### 9.1 정합성

테스트 종료 후 다음 항목을 확인합니다.

#### 초과 발급

```text
coupon_issue 발급 건수 <= total_quantity
```

#### 재고 정합성

```text
issued_quantity == 실제 성공 발급 건수
```

```text
total_quantity - remaining_quantity == 실제 성공 발급 건수
```

#### 중복 발급

동일한 쿠폰에 대해 한 사용자의 성공 발급 행은 최대 1건이어야 합니다.

```text
(coupon_id, user_id) 중복 성공 발급 = 0건
```

#### 중복 요청

동일한 `requestId`에 의해 생성된 발급 이력은 최대 1건이어야 합니다.

### 9.2 정합성 최종 판정 기준

전략의 재고 저장소에 따라 다음 기준으로 정합성 통과를 판정합니다.

DB 재고를 사용하는 `DIRECT`, `PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`은 다음 조건을 확인합니다.

```text
coupon_issue의 ISSUED 건수 == coupon_stock.issued_quantity

remaining_quantity
    == total_quantity - coupon_issue의 ISSUED 건수

issued_quantity + remaining_quantity
    == total_quantity

remaining_quantity >= 0

동일한 (coupon_id, user_id)의 유효 발급은 최대 1건

동일한 request_id의 발급 이력은 최대 1건
```

`REDIS`와 `REDIS_KAFKA`는 DB의 `coupon_stock.issued_quantity`, `remaining_quantity`를 재고 판정 기준으로 사용하지 않고 다음 조건을 확인합니다.

```text
total_quantity - Redis 잔여 재고 == coupon_issue의 ISSUED 건수

Redis 잔여 재고 >= 0

동일한 (coupon_id, user_id)의 유효 발급은 최대 1건

동일한 request_id의 발급 이력은 최대 1건
```

`REDIS_KAFKA`는 HTTP 요청 종료 시점이 아니라 Kafka Consumer 처리가 완료된 시점에 최종 정합성을 판정합니다. Kafka 대기 메시지와 처리 중 메시지가 0이 되기 전에 DB 발급 이력만 조회하면 정상 처리 중인 요청을 불일치로 오판할 수 있습니다.

`DIRECT`는 동시성 제어가 없는 기준 전략이므로 정합성 통과를 강제하지 않고, 발생한 불일치 자체를 비교 결과로 기록합니다.


## 10. 성능 지표

부하 테스트에서는 최소한 다음 항목을 수집합니다.

| Metric | 설명 |
| --- | --- |
| Throughput | 초당 처리 요청 수 |
| `http_req_duration avg` | 평균 응답시간 |
| `p90` | 응답시간 90 percentile |
| `p95` | 응답시간 95 percentile |
| `p99` | 응답시간 99 percentile |
| SUCCESS | 정상 발급 응답 수 |
| SOLD_OUT | 재고 소진 응답 수 |
| DUPLICATE | 중복 관련 응답 수 |
| INTERNAL_ERROR | 예상하지 않은 오류 수 |
| Network Error | 연결 실패 등 HTTP 요청 자체의 실패 |
| WAITING | Redis 선점 후 Kafka 비동기 처리를 기다리는 정상 접수 응답 |
| Consumer 완료 건수 | Kafka Consumer가 DB 발급 이력 저장을 완료한 수 |
| End-to-end 완료시간 | 첫 요청부터 모든 비동기 DB 저장 완료까지 걸린 시간 |
| Retry/DLQ | Kafka 처리 재시도 및 최종 실패 메시지 수 |

HTTP 상태코드만으로 결과를 판정하지 않습니다.

- 동기 발급 성공: HTTP 200이며 응답의 `result`가 `SUCCESS`
- 비동기 접수 성공: `REDIS_KAFKA`가 정한 정상 HTTP 상태이며 응답의 `result`가 `WAITING`
- 매진: HTTP 409이며 오류 `code`가 `EXPERIMENT409-0`
- 동일 요청: HTTP 409이며 오류 `code`가 `EXPERIMENT409-1`
- 동일 사용자: HTTP 409이며 오류 `code`가 `EXPERIMENT409-2`
- 시스템 오류: HTTP 500 이상 또는 네트워크 오류

`SOLD_OUT`, `DUPLICATE_REQUEST`, `DUPLICATE_USER`는 예상 가능한 비즈니스 응답이므로 시스템 오류와 분리해 집계합니다.

`REDIS_KAFKA`의 HTTP 상태와 응답 코드는 구현 완료 후 API 명세 및 k6 스크립트에 동일하게 반영합니다. `WAITING`은 최종 발급 완료가 아니라 정상적으로 비동기 처리에 접수되었다는 뜻입니다.


## 11. 테스트 도구

부하 테스트 도구는 k6를 사용합니다.

k6는 다음 역할을 담당합니다.

- 동시 요청 생성
- 요청 처리량 측정
- 응답시간 측정
- HTTP 오류 확인
- 응답 결과별 성공/실패 건수 집계

정식 비교 시나리오에서는 공통 k6 모듈을 사용하고, 전략이나 couponId 등 필요한 값만 변수로 변경하는 방식을 우선합니다.

예시:

```text
coupon-test.js

STRATEGY=DIRECT
STRATEGY=PESSIMISTIC
STRATEGY=OPTIMISTIC
STRATEGY=CONDITIONAL
STRATEGY=REDIS
STRATEGY=REDIS_KAFKA
```

현재 스크립트는 실험 A의 다섯 전략을 실행할 수 있습니다. 실험 B를 실행하려면 `REDIS_KAFKA`용 실행 파일과 다음 처리가 추가되어야 합니다.

- `WAITING`을 정상 접수 결과로 집계
- Redis 재고 및 중복 방지 키 초기화
- Kafka Consumer 처리 완료까지 상태를 폴링하거나 대기
- 최종 DB 발급 이력, Redis 잔여 재고, Retry/DLQ를 조회하여 정합성 판정


## 12. 실험 실행 순서

각 전략은 다음 순서로 실행합니다.

```text
1. 애플리케이션 및 DB 상태 확인

2. 대상 쿠폰 데이터 초기화
   - issued_quantity = 0
   - remaining_quantity = total_quantity
   - version = 0
   - 기존 coupon_issue 제거
   - Redis 전략은 재고 키와 requestId/userId 중복 방지 키 초기화

3. 부하 테스트 실행

4. 모든 HTTP 요청 종료 확인

5. REDIS_KAFKA는 Consumer 처리 완료 확인
   - 처리 대기/처리 중 메시지 0건
   - 또는 완료 건수 + 실패 건수 == 정상 접수 건수

6. k6 결과 저장
   - throughput
   - avg
   - p90
   - p95
   - p99
   - 결과별 요청 수

7. DB 및 Redis 최종 상태 조회

8. 정합성 검증

9. 결과 기록

10. 다음 전략 테스트를 위해 초기화
```
현재 초기화 API:

```http
POST /experiment/coupons/{couponId}/reset
```

Reset은 부하 요청이 모두 종료된 이후에만 수행합니다. `REDIS_KAFKA`는 HTTP 요청뿐 아니라 Kafka Consumer 처리까지 완료된 후 Reset합니다.


## 13. 반복 실행

단일 실행 결과만으로 전략을 평가하지 않습니다.

동일한 조건에서 각 전략을 여러 번 실행하고 결과의 편차를 확인합니다.

예시:

```text
DIRECT       1차 / 2차 / 3차
PESSIMISTIC  1차 / 2차 / 3차
OPTIMISTIC   1차 / 2차 / 3차
CONDITIONAL  1차 / 2차 / 3차
REDIS        1차 / 2차 / 3차
REDIS_KAFKA  1차 / 2차 / 3차
```

필요한 경우 최초 실행은 JVM 및 DB 워밍업을 위한 실행으로 분리하고 실제 비교 결과에서 제외할 수 있습니다.

각 비교 조건은 최소 3회 실행합니다. 최초 실행을 워밍업으로 분리할지와 워밍업 결과를 평균에서 제외할지는 최종 실험 전에 확정합니다.


## 14. 결과 기록 형식

실험 결과는 동일한 형식으로 기록합니다.

| 전략 | SUCCESS | SOLD_OUT | 기타 오류 | req/s | avg | p95 | p99 | issueCount | remaining | 정합성 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| DIRECT |  |  |  |  |  |  |  |  |  |  |
| PESSIMISTIC |  |  |  |  |  |  |  |  |  |  |
| OPTIMISTIC |  |  |  |  |  |  |  |  |  |  |
| CONDITIONAL |  |  |  |  |  |  |  |  |  |  |
| REDIS |  |  |  |  |  |  |  |  |  |  |
| REDIS_KAFKA |  |  |  |  |  |  |  |  |  |  |

실제 수치와 결과 분석은 `experiment-results.md`에서 관리합니다.


## 15. 실험 범위

이번 실험의 비교 대상은 다음 두 가지입니다.

- 쿠폰 재고 선점 시점의 동시성 제어 방식
- Redis 선점 이후 DB 발급 이력을 동기 또는 Kafka 비동기로 저장하는 처리 방식

따라서 다음 기능은 필요할 경우 별도 실험 또는 후속 설계에서 다룹니다.

- 이벤트 라이프사이클
- 알림 처리
- 쿠폰 사용/취소 상태 전이
- 운영 환경의 인증/인가
- 엄격한 응답 멱등성
- 최종 서비스 전체 범위의 장애 복구 및 reconciliation
- 다중 리전 환경

Redis 구현에서는 Redis와 DB 사이의 정합성 문제가 추가로 발생할 수 있으므로 DB 전략과 동일한 기준 외에 장애 및 보상 시나리오가 필요할 수 있습니다.

Kafka의 Retry/DLQ와 실패 복구는 Redis/Kafka 파이프라인의 최종 정합성을 확인하는 데 필요한 범위까지 실험에 포함합니다.


## 16. 실험 시 주의사항

### 테스트 도중 Reset 금지

부하 요청이 처리 중인 상태에서 Reset API를 실행하면 실험 결과가 오염될 수 있습니다.

`REDIS_KAFKA`에서는 k6의 HTTP 요청이 모두 끝났더라도 Consumer가 처리 중일 수 있으므로 Kafka 처리가 완료되기 전에는 Reset하지 않습니다.

### 전략 간 데이터 공유 금지

하나의 쿠폰에 서로 다른 전략을 섞어서 호출하지 않습니다.

### 테스트 사용자 재사용 주의

이전 테스트에서 생성된 `coupon_issue`가 남아 있으면 `DUPLICATE_USER`가 발생할 수 있으므로 다음 실험 전에 반드시 초기화 상태를 확인합니다.

### 임의의 userId 사용 금지

DB에 존재하지 않는 `userId`를 사용하면 FK 오류가 발생할 수 있으므로 준비된 테스트 사용자만 사용합니다.

### 환경 설정 기록

다음 항목을 변경했다면 결과 문서에 반드시 기록합니다.

- Tomcat thread/accept queue
- HikariCP pool size
- MySQL 설정
- JVM 옵션
- Redis 설정
- Kafka 파티션 수 및 Consumer 동시성
- k6 VU 및 요청 수
- 실행 머신


## 17. 테스트팀 우선 실행 범위

| 순서 | 작업 |
| ---: | --- |
| 1 | DB 4개 전략과 Redis 단독의 단건 발급 확인 |
| 2 | 다섯 전략을 재고 10 / 요청 20으로 스모크 테스트 |
| 3 | 다섯 전략을 100/200, 500/1,000, 1,000/2,000 순서로 비교 |
| 4 | 각 단계의 TPS·평균·p95·p99 및 DB/Redis 정합성 기록 |
| 5 | 동일 사용자 및 동일 requestId 시나리오 별도 검증 |
| 6 | Redis/Kafka 구현 완료 후 단건 발행·소비·DB 저장 확인 |
| 7 | Redis 단독과 Redis/Kafka를 10/20부터 1,000/2,000까지 동일 조건으로 비교 |
| 8 | Kafka Consumer 처리 완료 후 최종 정합성과 Retry/DLQ 확인 |
| 9 | 최종 선정 Redis/Kafka로 재고 10,000 / 요청 20,000 검증 |


## 18. 추후 확정할 항목

현재 문서는 실험 설계를 정리하기 위한 초안이며, 다음 내용은 실제 부하 테스트 전 최종 확정합니다.

- 최종 VU 수
- 최종 총 요청 수
- 테스트 반복 횟수
- 워밍업 수행 여부
- 테스트 실행 순서
- `REDIS_KAFKA` API 경로와 정상 접수 HTTP 상태/응답 코드
- Kafka Consumer 완료 확인 API 또는 폴링 기준
- Kafka 파티션 수와 Consumer 동시성
- Retry/DLQ 처리 및 집계 방식
- 20,000 동시 요청을 생성할 부하 발생기 사양과 분산 실행 여부
- DB Lock wait 등 서버 측 세부 지표 수집 여부
- Grafana 기반 실시간 대시보드 구성 여부
- Docker 기반 공통 실험 환경 적용 범위
