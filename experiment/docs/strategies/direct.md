# Direct DB

`DIRECT`는 별도의 동시성 제어 없이 일반적인 JPA 조회와 Entity 변경으로 쿠폰을 발급하는 전략입니다.

이 전략은 정합성을 보장하기 위한 운영용 구현이라기보다, 동시성 제어를 적용한 다른 전략과 비교하기 위한 **baseline** 역할을 합니다.

> 실험 전체 설계는 `concurrency-experiment.md`, 공통 구조는 `common-code.md`, 성능 측정 결과는 `experiment-results.md`에서 다룹니다.

---

## At a glance

| 항목 | 내용 |
| --- | --- |
| Strategy | `DIRECT` |
| 재고 조회 | 일반 `findById()` |
| 동시성 제어 | 없음 |
| 재고 변경 | Entity 변경 + JPA dirty checking |
| Transaction | 발급 과정 전체를 하나의 트랜잭션으로 처리 |
| 실험 역할 | 동시성 제어가 없는 기준선 |

---

## 1. Request flow

```text
요청
 ↓
중복 requestId 검사
 ↓
같은 couponId + userId 발급 여부 검사
 ↓
CouponStock 일반 조회
 ↓
remainingQuantity 확인
 ↓
CouponStock.issue()
 ↓
CouponIssue 저장
 ↓
Transaction Commit
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

## 2. Duplicate check

재고를 변경하기 전에 이미 처리된 요청인지 확인합니다.

```java
if (couponIssueRepository.existsByRequestId(request.requestId())) {
    // DUPLICATE_REQUEST
}

if (couponIssueRepository.existsByCoupon_IdAndUser_Id(
        couponId,
        request.userId())) {
    // DUPLICATE_USER
}
```

확인하는 값은 다음 두 가지입니다.

```text
requestId
couponId + userId
```

사전 조회는 중복 요청을 빠르게 구분하기 위한 단계입니다.

동시에 실행된 두 요청이 사전 조회를 모두 통과할 수 있으므로 최종 중복 방지는 `coupon_issue` 테이블의 UNIQUE 제약도 함께 담당합니다.

```text
UNIQUE (request_id)
UNIQUE (coupon_id, user_id)
```

---

## 3. Stock lookup

Direct 전략의 핵심은 일반 `findById()`를 사용한다는 점입니다.

```java
CouponStock stock = couponStockRepository.findById(couponId)
        .orElseThrow(...);
```

이 조회에는 별도의 동시성 제어를 적용하지 않습니다.

```text
PESSIMISTIC_WRITE  X
version 조건       X
조건부 UPDATE      X
Redis Lock         X
```

따라서 여러 요청이 같은 `CouponStock` 값을 동시에 조회할 수 있습니다.

---

## 4. Stock update

재고가 소진된 경우 발급을 중단합니다.

```java
if (stock.getRemainingQuantity() <= 0) {
    // SOLD_OUT
}
```

재고가 남아 있으면 Entity의 `issue()`를 호출합니다.

```java
stock.issue();
```

`issue()`에서는 메모리의 Entity 값을 변경합니다.

```java
public void issue() {
    if (remainingQuantity <= 0) {
        throw new IllegalStateException("Coupon stock is exhausted");
    }

    issuedQuantity++;
    remainingQuantity--;
}
```

이후 트랜잭션이 커밋될 때 JPA dirty checking을 통해 변경된 값이 DB에 반영됩니다.

---

## 5. Issue persistence

재고를 확보한 뒤 성공한 발급 정보를 `CouponIssue`로 저장합니다.

```java
couponIssueRepository.saveAndFlush(
        CouponIssue.builder()
                .coupon(couponRepository.getReferenceById(couponId))
                .user(userRepository.getReferenceById(request.userId()))
                .requestId(request.requestId())
                .build()
);
```

재고 변경과 `CouponIssue` INSERT는 같은 트랜잭션에 포함됩니다.

```text
CouponStock 변경
+
CouponIssue INSERT
+
COMMIT
```

`CouponIssue` 저장 과정에서 UNIQUE 또는 FK 제약 위반이 발생해 트랜잭션이 롤백되면 해당 요청의 재고 변경도 함께 롤백됩니다.

---

## 6. Why lost updates can occur

Direct 방식에는 같은 재고 행에 대한 동시 수정을 조정하는 장치가 없습니다.

재고가 100일 때 두 요청이 거의 동시에 실행된다고 가정합니다.

```text
DB remaining_quantity = 100

Request A
→ 100 조회

Request B
→ 100 조회

Request A
→ 자신이 읽은 값을 기준으로 99 저장

Request B
→ 자신이 읽은 값을 기준으로 99 저장
```

실제로는 두 건이 발급되었지만 DB 재고 값에는 한 번의 감소만 반영될 수 있습니다.

```text
coupon_issue = 2건
remaining_quantity 감소 = 1
```

이와 같이 한 트랜잭션의 변경 결과가 다른 트랜잭션의 변경 결과를 덮어쓰는 현상을 `lost update`라고 합니다.

따라서 Direct는 다음 질문에 대한 기준선을 제공합니다.

> 별도의 동시성 제어가 없다면 동일한 쿠폰에 요청이 집중될 때 재고 정합성이 어떻게 달라지는가?

---

## 7. API

공통 발급 API에서 `DIRECT` 전략을 지정합니다.

```http
POST /experiment/coupons/{couponId}/issue?strategy=DIRECT
```

Request:

```json
{
  "userId": 1,
  "requestId": "direct-request-001"
}
```

---

## 8. What to check

Direct 전략에서는 다음 항목을 확인합니다.

```text
- 일반 findById()를 사용하는가
- PESSIMISTIC_WRITE를 사용하지 않는가
- 정상 요청에서 CouponIssue가 저장되는가
- 트랜잭션 실패 시 재고 변경도 롤백되는가
- 동시 요청에서 lost update가 발생할 수 있는가
- 최종 issueCount와 재고 감소량이 일치하는가
```

Direct는 정합성 실패 자체도 실험 결과가 될 수 있으므로 단순히 HTTP 성공 건수만으로 평가하지 않습니다.

---

## 9. Related files

```text
coupon/
├── entity/
│   └── CouponStock.java
├── repository/
│   └── CouponStockRepository.java
└── service/
    └── DirectCouponIssueServiceImpl.java

issue/
├── entity/
│   └── CouponIssue.java
└── repository/
    └── CouponIssueRepository.java
```

관련 문서:

- [동시성 실험 설계](./concurrency-experiment.md)
- [실험 공통 코드 구조](./common-code.md)
- [동시성 실험 결과](./experiment-results.md)
