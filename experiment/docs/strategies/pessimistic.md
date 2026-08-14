# Pessimistic Lock

`PESSIMISTIC`은 쿠폰 재고를 조회하는 시점에 DB 쓰기 락을 획득하여, 동일한 재고 행을 수정하려는 요청을 순차적으로 처리하는 전략입니다.

Direct 전략과 동일한 발급 흐름을 사용하되, `CouponStock` 조회에 `PESSIMISTIC_WRITE`를 적용한다는 점이 핵심 차이입니다.

> 실험 전체 설계는 `concurrency-experiment.md`, 공통 구조는 `common-code.md`, 성능 측정 결과는 `experiment-results.md`에서 다룹니다.

---

## At a glance

| 항목 | 내용 |
| --- | --- |
| Strategy | `PESSIMISTIC` |
| 재고 조회 | `findByIdWithPessimisticLock()` |
| 동시성 제어 | DB Pessimistic Write Lock |
| JPA LockMode | `PESSIMISTIC_WRITE` |
| Transaction | 발급 과정 전체를 하나의 트랜잭션으로 처리 |
| 실험 역할 | DB Lock 기반 정합성 제어 |

---

## 1. Request flow

```text
요청
 ↓
중복 requestId 검사
 ↓
같은 couponId + userId 발급 여부 검사
 ↓
CouponStock 조회 + PESSIMISTIC_WRITE
 ↓
재고 행 Lock 획득
 ↓
remainingQuantity 확인
 ↓
CouponStock.issue()
 ↓
CouponIssue 저장
 ↓
Transaction Commit
 ↓
Lock 해제
```

서비스 전체는 하나의 트랜잭션 안에서 실행됩니다.

```java
@Transactional
@Override
public CouponIssueResponse issue(
        Long couponId,
        CouponIssueRequest request
) {
    ...
}
```

---

## 2. Lock acquisition

비관적 락은 Repository의 조회 메서드에 선언합니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select stock from CouponStock stock where stock.couponId = :couponId")
Optional<CouponStock> findByIdWithPessimisticLock(
        @Param("couponId") Long couponId
);
```

Service에서는 일반 `findById()` 대신 해당 메서드를 사용합니다.

```java
CouponStock stock =
        couponStockRepository.findByIdWithPessimisticLock(couponId)
                .orElseThrow(...);
```

`PESSIMISTIC_WRITE`는 조회한 행을 수정 대상으로 잠급니다.

MySQL에서는 해당 재고 행에 `SELECT ... FOR UPDATE`와 같은 성격의 쓰기 락이 적용됩니다.

---

## 3. Concurrent requests

동일한 쿠폰에 두 요청이 동시에 들어오는 경우를 가정합니다.

```text
Request A
→ CouponStock Lock 획득
→ remaining = 100

Request B
→ 같은 CouponStock Lock 요청
→ 대기
```

A가 재고를 변경하고 발급 정보를 저장한 뒤 트랜잭션을 커밋합니다.

```text
Request A
→ remaining = 99
→ CouponIssue INSERT
→ COMMIT
→ Lock 해제
```

그 뒤 B가 락을 획득합니다.

```text
Request B
→ Lock 획득
→ 최신 remaining = 99 확인
→ remaining = 98
→ CouponIssue INSERT
→ COMMIT
```

동일한 재고 행을 수정하려는 요청은 먼저 락을 획득한 트랜잭션이 끝날 때까지 기다리게 됩니다.

이 방식으로 각 요청은 이전 요청이 반영한 최신 재고를 기준으로 다음 발급을 처리할 수 있습니다.

---

## 4. Lock lifetime

락은 조회 시점에 획득하고 트랜잭션이 종료될 때 해제됩니다.

```text
Transaction Start

중복 검사
 ↓
SELECT + PESSIMISTIC_WRITE
 ↓
Lock 획득
 ↓
재고 확인
 ↓
재고 변경
 ↓
CouponIssue INSERT
 ↓
COMMIT

Transaction End
Lock 해제
```

따라서 Lock의 보호 범위는 단순 조회 순간만이 아니라 재고 변경과 발급 이력 저장까지 포함합니다.

---

## 5. Stock update

락을 획득한 후 재고가 남아 있는지 확인합니다.

```java
if (stock.getRemainingQuantity() <= 0) {
    // SOLD_OUT
}
```

재고가 있으면 Direct와 동일하게 Entity의 `issue()`를 호출합니다.

```java
stock.issue();
```

```java
public void issue() {
    if (remainingQuantity <= 0) {
        throw new IllegalStateException("Coupon stock is exhausted");
    }

    issuedQuantity++;
    remainingQuantity--;
}
```

Pessimistic 전략에서 중요한 부분은 `issue()` 자체가 특별한 것이 아니라, **이 메서드를 호출하기 전에 해당 재고 행의 DB Lock을 확보했다는 점**입니다.

---

## 6. Issue persistence and rollback

재고를 감소시킨 뒤 성공한 발급 정보를 저장합니다.

```java
couponIssueRepository.saveAndFlush(
        CouponIssue.builder()
                .coupon(couponRepository.getReferenceById(couponId))
                .user(userRepository.getReferenceById(request.userId()))
                .requestId(request.requestId())
                .build()
);
```

다음 작업은 동일한 트랜잭션에서 처리됩니다.

```text
DB Lock 획득
+
CouponStock 변경
+
CouponIssue INSERT
+
COMMIT
```

`CouponIssue` 저장 과정에서 UNIQUE/FK 위반 등으로 트랜잭션이 롤백되면 재고 변경도 함께 롤백되고, 트랜잭션 종료와 함께 락도 해제됩니다.

---

## 7. Shared duplicate safeguards

Pessimistic Lock은 재고 경쟁을 제어하기 위한 방식입니다.

같은 사용자나 동일 요청의 중복 발급은 별도의 DB 제약으로도 보호합니다.

```text
UNIQUE (request_id)
UNIQUE (coupon_id, user_id)
```

재고 락과 중복 발급 제약의 역할은 구분됩니다.

```text
PESSIMISTIC_WRITE
→ 동일 재고의 동시 수정 제어

UNIQUE (request_id)
→ 동일 요청의 중복 성공 방지

UNIQUE (coupon_id, user_id)
→ 같은 사용자의 동일 쿠폰 중복 성공 방지
```

---

## 8. Trade-offs

Pessimistic Lock은 동일한 DB를 사용하는 여러 애플리케이션 인스턴스에서도 같은 재고 행에 대한 락을 공유할 수 있다는 장점이 있습니다.

반면 동일한 쿠폰에 요청이 집중될수록 하나의 행 락 앞에서 요청이 대기하게 됩니다.

```text
Request A ───── Lock 획득 ───── COMMIT
Request B ─────── 대기 ──────── Lock 획득
Request C ───────── 대기 ───────────── Lock 획득
Request D ─────────── 대기 ──────────────────── ...
```

따라서 높은 경합 상황에서는 다음 항목을 함께 관찰해야 합니다.

```text
- 응답시간 증가
- Lock wait 증가
- DB Connection 점유 시간 증가
- 처리량 변화
- Lock timeout / deadlock 발생 여부
```

현재 구현에 별도의 lock timeout 또는 deadlock 재시도 정책이 없다면, 해당 예외는 실험 결과에서 별도로 확인해야 합니다.

---

## 9. API

공통 발급 API에서 `PESSIMISTIC` 전략을 지정합니다.

```http
POST /experiment/coupons/{couponId}/issue?strategy=PESSIMISTIC
```

Request:

```json
{
  "userId": 1,
  "requestId": "pessimistic-request-001"
}
```

---

## 10. What to check

Pessimistic 전략에서는 다음 항목을 확인합니다.

```text
- Repository 조회에 PESSIMISTIC_WRITE가 적용되는가
- 같은 재고를 동시에 수정할 때 요청이 직렬화되는가
- 발급 수량이 초기 재고를 초과하지 않는가
- remaining_quantity가 음수가 되지 않는가
- issueCount와 재고 감소량이 일치하는가
- 트랜잭션 종료까지 Lock이 유지되는가
- 높은 경합에서 Lock wait와 응답시간이 어떻게 변하는가
```

기본 동시성 시나리오가 재고 100개, 요청 200건이라면 정합성을 보장하는 실행의 기대 상태는 다음과 같습니다.

```text
SUCCESS = 100
SOLD_OUT = 100

issueCount = 100
remaining_quantity = 0
consistent = true
```

---

## 11. Related files

```text
coupon/
├── entity/
│   └── CouponStock.java
├── repository/
│   └── CouponStockRepository.java
└── service/
    └── PessimisticCouponIssueServiceImpl.java

issue/
├── entity/
│   └── CouponIssue.java
└── repository/
    └── CouponIssueRepository.java
```

관련 문서:

- [동시성 실험 설계](../concurrency-experiment.md)
- [실험 공통 코드 구조](../common-code.md)
- [동시성 실험 결과](../experiment-results.md)
