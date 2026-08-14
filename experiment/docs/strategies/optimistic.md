# OPTIMISTIC — 낙관적 락 (버전 기반 CAS)

이 문서는 낙관적 락 전략(`OptimisticCouponIssueServiceImpl`)의 동작 원리와 구현 방식을 설명합니다.

공통 API, DTO, 예외 구조는 [실험 공통 코드 구조](../common-code.md)를 따릅니다.

---

## 원리

`PESSIMISTIC` 전략과 달리 SELECT 시점에 row를 잠그지 않습니다.

대신 조회한 `version` 값을 그대로 UPDATE의 WHERE절에 걸어 compare-and-swap 방식으로 재고를 차감합니다.

```text
1. 재고 조회 (락 없음) → version 확인
2. UPDATE ... WHERE couponId = ? AND version = ?
3. 반영된 row가 1건 → 성공
4. 반영된 row가 0건 → 그 사이 다른 트랜잭션이 먼저 커밋해 version이 바뀐 것(충돌) → 재조회 후 재시도
```

`CouponStock`은 여러 전략이 공유하는 엔티티이므로 JPA의 `@Version` 자동 관리 기능은 의도적으로 사용하지 않고, 버전 비교와 증가를 직접 쿼리로 구현합니다.

---

## 쿼리

```java
@Modifying(clearAutomatically = true)
@Query("update CouponStock stock "
        + "set stock.issuedQuantity = stock.issuedQuantity + 1, "
        + "stock.remainingQuantity = stock.remainingQuantity - 1, "
        + "stock.version = stock.version + 1, "
        + "stock.updatedAt = CURRENT_TIMESTAMP "
        + "where stock.couponId = :couponId and stock.version = :version")
int issueIfVersionMatches(@Param("couponId") Long couponId, @Param("version") Long version);
```

조회한 `version`과 DB의 현재 `version`이 같을 때만 UPDATE가 적용되며, 성공 시 `version`도 함께 1 증가합니다.

---

## 재시도와 격리 수준

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
@Override
public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
    rejectDuplicate(couponId, request);

    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
        CouponStock stock = couponStockRepository.findById(couponId)...;
        if (stock.getRemainingQuantity() <= 0) {
            throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
        }

        int updated = couponStockRepository.issueIfVersionMatches(couponId, stock.getVersion());
        if (updated == 1) {
            saveIssue(couponId, request);
            return CouponIssueResponse.success(couponId, request);
        }
    }

    throw new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR);
}
```

| 항목 | 값 | 설명 |
| --- | --- | --- |
| `MAX_RETRY` | 10 | 재시도 상한. 소진되면 `INTERNAL_SERVER_ERROR` |
| 트랜잭션 격리 수준 | `READ_COMMITTED` | 아래 참고 |

### 왜 `READ_COMMITTED`인가

MySQL 기본 격리 수준인 `REPEATABLE READ`에서는 트랜잭션 시작 시점에 스냅샷이 고정됩니다. 같은 트랜잭션 안에서 충돌 후 `findById()`를 반복 호출해도 계속 예전 `version`만 읽게 되어 "충돌 → 재조회 → 또 충돌"이 끝없이 반복될 수 있습니다.

`READ_COMMITTED`로 낮추면 매 SELECT마다 그 시점의 최신 커밋 데이터를 읽으므로 재시도가 실제로 최신 `version`을 향해 수렴합니다.

---

## 중복 방지

`PESSIMISTIC`, `CONDITIONAL`과 동일한 2단계 방어를 사용합니다.

1. 발급 전 `existsByRequestId` / `existsByCoupon_IdAndUser_Id`로 사전 차단
2. 사전 체크를 동시에 통과한 요청은 `CouponIssue`의 unique 제약(`uq_request_id`, `uq_coupon_user`)에서 `DataIntegrityViolationException`으로 최종 차단 → `DUPLICATE_REQUEST` / `DUPLICATE_USER`로 변환

---

## 예외 매핑

| 상황 | 결과 |
| --- | --- |
| 재고 없음 (조회 시점) | `SOLD_OUT` |
| `version` 충돌 후 재시도 성공 | `SUCCESS` |
| `MAX_RETRY` 소진 | `INTERNAL_SERVER_ERROR` |
| 중복 `requestId` | `DUPLICATE_REQUEST` |
| 중복 `(couponId, userId)` | `DUPLICATE_USER` |

---

## 장단점

### 장점

- 정상 경합 상황에서는 락 대기 없이 짧게 UPDATE 한 번으로 끝남
- 커넥션을 오래 붙잡지 않아 `PESSIMISTIC` 대비 스레드/커넥션 점유 시간이 짧음

### 단점

- 경합이 심할수록 재시도 횟수가 늘어나고, 재시도마다 재조회 쿼리가 추가로 발생
- `MAX_RETRY` 소진 시 실제로는 재고가 있어도 실패 처리될 수 있음(안전장치성 실패)
- 격리 수준을 `READ_COMMITTED`로 낮춰야 하므로 다른 부분에서 기대하는 격리 수준과 달라질 수 있음(실험 범위 내 트레이드오프)

---

## 관련 문서

- [동시성 실험 설계](../concurrency-experiment.md)
- [실험 공통 코드 구조](../common-code.md)
- [CONDITIONAL](./conditional.md)
