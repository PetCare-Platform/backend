# CONDITIONAL — 조건부 UPDATE

이 문서는 조건부 UPDATE 전략(`ConditionalCouponIssueServiceImpl`)의 동작 원리와 구현 방식을 설명합니다.

공통 API, DTO, 예외 구조는 [실험 공통 코드 구조](../common-code.md)를 따릅니다.

---

## 원리

`PESSIMISTIC`처럼 미리 잠그지도, `OPTIMISTIC`처럼 `version`을 비교하지도 않습니다.

대신 "재고가 남아있는가"라는 비즈니스 조건 자체를 UPDATE의 WHERE절에 걸어 조회와 차감을 한 문장으로 원자적으로 처리합니다.

```text
UPDATE ... WHERE couponId = ? AND remainingQuantity > 0
```

조건이 실패하면(반영 0건) 그 시점에 정말 재고가 없다는 뜻이므로 `OPTIMISTIC`과 달리 재시도가 필요 없습니다.

---

```java
@Modifying(clearAutomatically = true)
@Query("update CouponStock stock "
		+ "set stock.issuedQuantity = stock.issuedQuantity + 1, "
		+ "stock.remainingQuantity = stock.remainingQuantity - 1, "
		+ "stock.updatedAt = CURRENT_TIMESTAMP "
		+ "where stock.couponId = :couponId and stock.remainingQuantity > 0")
int issueIfStockAvailable(@Param("couponId") Long couponId);
```

`version` 컬럼을 전혀 사용하지 않고, `remainingQuantity > 0` 조건 자체가 DB 엔진 수준에서 원자적으로 평가됩니다.

---

## 처리 흐름

```java
@Transactional
@Override
public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
	rejectDuplicate(couponId, request);

	int updated = couponStockRepository.issueIfStockAvailable(couponId);

	if (updated == 0) {
		if (!couponStockRepository.existsById(couponId)) {
			throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
			}
		throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
	}

	saveIssue(couponId, request);
	return CouponIssueResponse.success(couponId, request);
}
```

`OPTIMISTIC`과 달리 반복문/재시도 카운터가 없습니다. UPDATE 결과가 0건이면 바로 실패로 확정합니다.

0건일 때 원인을 두 가지로 구분합니다.

| 반영 건수 | 쿠폰 존재 여부 | 결과 |
| --- | --- | --- |
| 1 | - | `SUCCESS` |
| 0 | 존재하지 않음 | `COUPON_NOT_FOUND` |
| 0 | 존재하지만 재고 소진 | `SOLD_OUT` |

---

## 중복 방지

`OPTIMISTIC`, `PESSIMISTIC`과 동일한 2단계 방어를 사용합니다.

1. 발급 전 `existsByRequestId` / `existsByCoupon_IdAndUser_Id`로 사전 차단
2. 사전 체크를 동시에 통과한 요청은 `CouponIssue`의 unique 제약(`uq_request_id`, `uq_coupon_user`)에서 `DataIntegrityViolationException`으로 최종 차단 -> `DUPLICATE_REQUEST` / `DUPLICATE_USER`로 변환

---

## OPTIMISTIC과의 차이

| 구분 | OPTIMISTIC | CONDITIONAL |
| --- | --- | --- |
| WHERE 조건 | `version = :version` | `remainingQuantity > 0` |
| 조회 후 재시도 | 필요 (`MAX_RETRY`) | 불필요 |
| 트랜잭션 격리 수준 | `READ_COMMITTED`로 조정 | 기본값 사용 |
| 충돌 시 판단 | "값이 바뀌었을 수도 있음" → 재조회해서 확인 | "조건이 거짓" → 바로 실패 확정 |

---

## 장단점

### 장점

- 재시도 로직이 없어 구현과 흐름이 단순함
- UPDATE 실패가 곧 "재고 없음"으로 바로 확정되므로 불필요한 재조회 쿼리가 없음
- 격리 수준을 기본값 그대로 사용 가능

### 단점

- 실패 원인이 "충돌"과 "진짜 소진"으로 구분되지 않고 조건 불일치 하나로 뭉뚱그려짐(재시도 여지가 있는 경합인지 확인 불가)
- `version` 같은 변경 이력 추적 수단이 없어 이 쿼리 하나만 봐서는 몇 번의 갱신을 거쳤는지 알 수 없음

---

## 관련 문서

- [동시성 실험 설계](../concurrency-experiment.md)
- [실험 공통 코드 구조](../common-code.md)
- [OPTIMISTIC](./optimistic.md)
