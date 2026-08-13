# 쿠폰 발급 동시성 실험 결과

## 1. 개요

본 문서는 각 쿠폰 발급 동시성 제어 전략의 실제 실험 결과를 기록한다.

실험 설계 및 조건은 다음 문서를 기준으로 한다.

- [동시성 실험 설계](./concurrency-experiment.md)
- [공통 코드 구조](./common-code.md)

> [!IMPORTANT]
> 측정하지 않은 값은 예상값으로 채우지 않고 `-`로 기록한다.

---

# 2. 공통 실험 환경

## Application

| 항목 | 값 |
|---|---|
| Branch / Commit | |
| Java | |
| Spring Boot | |
| 실행 환경 | |
| 실행 날짜 | |

## Database

| 항목 | 값 |
|---|---|
| DBMS | MySQL |
| Version | |
| Connection Pool | |
| Isolation Level | |
| 실행 환경 | |

## 실험 조건

| 항목 | 값 |
|---|---:|
| 초기 쿠폰 재고 | 100 |
| 전체 요청 수 | 200 |
| Worker 수 | 200 |
| 사용자 수 | 200 |
| 요청별 userId | Unique |
| 요청별 requestId | Unique |

---

# 3. 전체 결과

| 전략 | 성공 | 실패 | 최종 재고 | Issue Row | 정합성 | 전체 시간 | 평균 Latency | 최대 Latency |
|---|---:|---:|---:|---:|---|---:|---:|---:|
| DIRECT | - | - | - | - | - | - | - | - |
| PESSIMISTIC | - | - | - | - | - | - | - | - |
| OPTIMISTIC | - | - | - | - | - | - | - | - |
| CONDITIONAL | - | - | - | - | - | - | - | - |
| REDIS | - | - | - | - | - | - | - | - |

---

# 4. DIRECT

## 4.1 실험 목적

동시성 제어가 없는 상태에서 다수의 요청이 동일한 재고에 접근했을 때 발생하는 현상을 확인한다.

`DIRECT` 결과는 다른 동시성 제어 전략의 비교 기준선으로 사용한다.

---

## 4.2 실험 결과

| 항목 | 결과 |
|---|---:|
| 전체 요청 | 200 |
| 성공 | |
| 실패 | |
| 최종 재고 | |
| Issue Row | |
| consistent | |
| 전체 실행 시간 | |
| 평균 Latency | |
| 최대 Latency | |

---

## 4.3 정합성 검증

- [ ] 초과 발급 없음
- [ ] `remainingQuantity >= 0`
- [ ] `issueCount == totalQuantity - remainingQuantity`
- [ ] `issuedQuantity == issueCount`
- [ ] 동일 사용자 중복 없음
- [ ] 동일 requestId 중복 없음

---

## 4.4 관찰 내용

-

---

## 4.5 결과 분석

-

---

# 5. PESSIMISTIC

## 5.1 실험 목적

DB의 `PESSIMISTIC_WRITE`를 사용하여 동일한 `CouponStock`에 대한 변경을 직렬화했을 때 정합성과 성능을 확인한다.

---

## 5.2 실험 결과

| 항목 | 결과 |
|---|---:|
| 전체 요청 | 200 |
| 성공 | |
| 실패 | |
| 최종 재고 | |
| Issue Row | |
| consistent | |
| 전체 실행 시간 | |
| 평균 Latency | |
| 최대 Latency | |

---

## 5.3 정합성 검증

- [ ] 성공 건수가 최초 재고 이하
- [ ] 최종 재고가 음수가 아님
- [ ] 발급 건수와 재고 차감 수 일치
- [ ] Issue Row 수와 발급 수량 일치
- [ ] 중복 사용자 발급 없음
- [ ] 중복 requestId 없음

---

## 5.4 실패 결과

가능하면 실패 결과를 원인별로 기록한다.

| 실패 원인 | 건수 |
|---|---:|
| SOLD_OUT | |
| DUPLICATE_REQUEST | |
| DUPLICATE_USER | |
| LOCK 관련 오류 | |
| 기타 | |

---

## 5.5 관찰 내용

-

---

## 5.6 결과 분석

-

---

# 6. OPTIMISTIC

> [!NOTE]
> 구현 및 실험 완료 후 작성한다.

## 6.1 실험 결과

| 항목 | 결과 |
|---|---:|
| 전체 요청 | |
| 성공 | |
| 실패 | |
| Retry | |
| Optimistic Lock 충돌 | |
| 최종 재고 | |
| Issue Row | |
| consistent | |
| 전체 실행 시간 | |
| 평균 Latency | |

## 6.2 분석

-

---

# 7. CONDITIONAL

> [!NOTE]
> 구현 및 실험 완료 후 작성한다.

## 7.1 실험 결과

| 항목 | 결과 |
|---|---:|
| 전체 요청 | |
| 성공 | |
| 실패 | |
| 조건부 UPDATE 성공 | |
| 조건부 UPDATE 실패 | |
| 최종 재고 | |
| Issue Row | |
| consistent | |
| 전체 실행 시간 | |
| 평균 Latency | |

## 7.2 분석

-

---

# 8. REDIS

> [!NOTE]
> 구현 및 실험 완료 후 작성한다.

## 8.1 실험 결과

| 항목 | 결과 |
|---|---:|
| 전체 요청 | |
| 성공 | |
| 실패 | |
| Redis 최종 재고 | |
| DB 최종 재고 | |
| Issue Row | |
| consistent | |
| 전체 실행 시간 | |
| 평균 Latency | |

## 8.2 Redis / DB 정합성

```text
Redis Remaining Quantity
        vs
DB Remaining Quantity
```

결과:

-

## 8.3 분석

-

---

# 9. 전략별 정합성 비교

| 전략 | 초과 발급 | 재고 정합성 | 중복 방지 | 최종 판정 |
|---|---|---|---|---|
| DIRECT | - | - | - | - |
| PESSIMISTIC | - | - | - | - |
| OPTIMISTIC | - | - | - | - |
| CONDITIONAL | - | - | - | - |
| REDIS | - | - | - | - |

---

# 10. 전략별 성능 비교

| 전략 | 전체 시간 | 평균 Latency | 최대 Latency | 처리량 |
|---|---:|---:|---:|---:|
| DIRECT | - | - | - | - |
| PESSIMISTIC | - | - | - | - |
| OPTIMISTIC | - | - | - | - |
| CONDITIONAL | - | - | - | - |
| REDIS | - | - | - | - |

---

# 11. 실험 결과 해석

## 정합성

-

## 성능

-

## 구현 복잡도

-

## 운영 관점

-

---

# 12. 전략별 장단점

## DIRECT

### 장점

-

### 단점

-

---

## PESSIMISTIC

### 장점

-

### 단점

-

---

## OPTIMISTIC

### 장점

-

### 단점

-

---

## CONDITIONAL

### 장점

-

### 단점

-

---

## REDIS

### 장점

-

### 단점

-

---

# 13. 최종 비교

각 전략을 다음 관점에서 평가한다.

| 평가 기준 | DIRECT | PESSIMISTIC | OPTIMISTIC | CONDITIONAL | REDIS |
|---|---|---|---|---|---|
| 데이터 정합성 | | | | | |
| 처리량 | | | | | |
| 응답 시간 | | | | | |
| 구현 복잡도 | | | | | |
| 운영 복잡도 | | | | | |
| 높은 경쟁 상황 | | | | | |
| 낮은 경쟁 상황 | | | | | |

---

# 14. 결론

## 가장 안정적인 전략

-

## 가장 높은 처리량을 보인 전략

-

## 높은 경쟁 상황에 적합한 전략

-

## 낮은 경쟁 상황에 적합한 전략

-

## 최종 선택 및 이유

-

---

# 15. 추가 실험 필요 사항

- [ ] 요청 수 증가 실험
- [ ] 재고 수 증가/감소 실험
- [ ] Worker 수 변경
- [ ] 동일 사용자 중복 요청 실험
- [ ] 동일 requestId 동시 요청 실험
- [ ] 반복 실행에 따른 결과 편차 확인
- [ ] DB Connection Pool 크기에 따른 영향
- [ ] Lock Timeout 상황 확인
- [ ] 장시간 부하 테스트