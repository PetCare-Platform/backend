# 실험 공통 코드 구조

쿠폰 동시성 실험은 모든 전략이 같은 API와 데이터 모델을 사용하고, **발급 방식만 전략별 구현체로 교체**할 수 있도록 구성되어 있습니다.

이 문서는 각 동시성 제어 방식의 원리나 성능 결과가 아니라, 여러 전략이 함께 사용하는 공통 코드의 구조와 확장 방법을 설명합니다.

- [동시성 실험 설계](./concurrency-experiment.md)
- [동시성 실험 결과](./experiment-results.md)

---

## 구조 한눈에 보기

```text
HTTP Request
    ↓
ExperimentCouponController
    ↓
CouponIssueServiceResolver
    ↓
CouponIssueService
    ↓
Strategy Implementation
    ↓
Repository / Database
```

발급 API는 하나만 사용합니다.

```http
POST /experiment/coupons/{couponId}/issue?strategy={STRATEGY}
```

예를 들어 같은 API에서 `strategy` 값만 바꾸면 다른 발급 로직을 실행합니다.

```text
DIRECT
PESSIMISTIC
OPTIMISTIC
CONDITIONAL
REDIS
```

따라서 Controller, 요청/응답 DTO, Entity와 실험 관리 기능은 공통으로 유지하고 각 전략은 발급 로직에만 집중할 수 있습니다.

---

## 주요 구성 요소

```text
com.mycom.petcoupon.experiment
├── coupon
│   ├── controller
│   │   └── ExperimentCouponController
│   ├── dto
│   ├── entity
│   ├── repository
│   ├── service
│   │   ├── CouponExperimentService
│   │   ├── CouponIssueService
│   │   ├── CouponIssueServiceResolver
│   │   └── *CouponIssueServiceImpl
│   └── type
│       └── CouponIssueStrategy
├── issue
├── user
└── global
    └── exception
```

| 구성 요소 | 역할 |
| --- | --- |
| `ExperimentCouponController` | 공통 실험 API 제공 |
| `CouponIssueService` | 모든 발급 전략의 공통 계약 |
| `CouponIssueServiceResolver` | 요청된 전략에 맞는 Service 선택 |
| `CouponIssueStrategy` | 지원하는 발급 전략 정의 |
| DTO | 전략과 관계없이 동일한 요청/응답 형식 제공 |
| Entity / Repository | 공통 데이터 모델과 저장소 제공 |
| `CouponExperimentService` | 쿠폰 생성, 상태 조회, reset 관리 |
| `global.exception` | 공통 오류 코드와 응답 형식 관리 |

---

## CouponIssueService

모든 쿠폰 발급 전략은 `CouponIssueService`를 구현합니다.

```java
public interface CouponIssueService {

    CouponIssueResponse issue(Long couponId, CouponIssueRequest request);

    CouponIssueStrategy supports();
}
```

`issue()`는 실제 발급을 수행하고, `supports()`는 구현체가 담당하는 전략을 반환합니다.

```java
@Override
public CouponIssueStrategy supports() {
    return CouponIssueStrategy.PESSIMISTIC;
}
```

각 구현체는 동일한 입력과 출력 형식을 사용하되 내부 발급 방식만 다르게 구현합니다.

---

## CouponIssueServiceResolver

Controller가 전략별 Service를 직접 선택하지 않도록 `CouponIssueServiceResolver`를 사용합니다.

Spring이 등록된 모든 `CouponIssueService` Bean을 주입하면 Resolver는 각 구현체의 `supports()` 값을 기준으로 `EnumMap`에 저장합니다.

```java
public CouponIssueServiceResolver(List<CouponIssueService> serviceList) {
    EnumMap<CouponIssueStrategy, CouponIssueService> servicesByStrategy =
            new EnumMap<>(CouponIssueStrategy.class);

    for (CouponIssueService service : serviceList) {
        CouponIssueStrategy strategy = service.supports();
        CouponIssueService existingService =
                servicesByStrategy.putIfAbsent(strategy, service);

        if (existingService != null) {
            throw new IllegalStateException(
                    "Duplicate coupon issue strategy: " + strategy);
        }
    }

    this.services = Map.copyOf(servicesByStrategy);
}
```

발급 요청에서는 전략에 맞는 구현체를 찾아 실행합니다.

```java
couponIssueServiceResolver
        .resolve(strategy)
        .issue(couponId, request);
```

같은 전략을 담당하는 Service가 두 개 등록되면 애플리케이션 초기화 과정에서 예외를 발생시킵니다. 지원하지 않는 전략을 조회하는 경우에도 예외를 발생시켜 잘못된 매핑을 조기에 확인합니다.

---

## 공통 Controller

`ExperimentCouponController`는 전략별 endpoint를 따로 만들지 않습니다.

```java
@PostMapping("/coupons/{couponId}/issue")
public CouponIssueResponse issue(
        @PathVariable("couponId") Long couponId,
        @RequestParam("strategy") CouponIssueStrategy strategy,
        @Valid @RequestBody CouponIssueRequest request) {

    return couponIssueServiceResolver
            .resolve(strategy)
            .issue(couponId, request);
}
```

현재 실험 API는 다음과 같습니다.

```text
POST /experiment/coupons
POST /experiment/coupons/{couponId}/issue?strategy={STRATEGY}
GET  /experiment/coupons/{couponId}/status
POST /experiment/coupons/{couponId}/reset
```

Controller는 HTTP 요청을 받고 공통 DTO로 변환한 뒤, Resolver를 통해 선택된 Service로 요청을 전달합니다.

---

## 공통 요청과 응답

모든 발급 전략은 동일한 `CouponIssueRequest`와 `CouponIssueResponse`를 사용합니다.

### Request

```json
{
  "userId": 1,
  "requestId": "request-001"
}
```

| 필드 | 검증 조건 |
| --- | --- |
| `userId` | 필수, 양수 |
| `requestId` | 필수, 공백 불가, 최대 64자 |

입력값 검증은 Controller의 `@Valid`와 Jakarta Validation을 통해 공통으로 처리합니다.

### Response

```json
{
  "couponId": 1,
  "userId": 1,
  "requestId": "request-001",
  "result": "SUCCESS"
}
```

발급 결과는 `CouponIssueResult`로 통일합니다.

```text
SUCCESS
SOLD_OUT
DUPLICATE_REQUEST
DUPLICATE_USER
COUPON_NOT_FOUND
INVALID_REQUEST
INTERNAL_ERROR
```

전략마다 별도의 DTO를 만들지 않아 같은 기준으로 요청과 결과를 비교할 수 있습니다.

---

## 공통 데이터와 Repository

실험 전략은 `Coupon`, `CouponStock`, `CouponIssue`, `User`를 공통으로 사용합니다.

```text
User ─── CouponIssue ─── Coupon ─── CouponStock
```

Repository 역시 Spring Data JPA를 기반으로 공통 사용합니다.

```text
CouponRepository
CouponStockRepository
CouponIssueRepository
UserRepository
```

기본 CRUD, 중복 확인, 발급 건수 조회와 같은 기능은 공통 Repository에서 제공합니다. 특정 전략에 별도의 DB 쿼리가 필요한 경우 해당 Repository에 필요한 메서드를 추가해 사용합니다.

예를 들어 비관적 락 전략은 `CouponStockRepository`에 별도의 재고 조회 메서드를 사용합니다.

```java
Optional<CouponStock> findByIdWithPessimisticLock(Long couponId);
```

각 전략의 쿼리와 동시성 처리 방식은 [동시성 실험 설계](./concurrency-experiment.md)에서 다룹니다.

---

## 실험 관리 기능

발급 전략과 직접 관련이 없는 기능은 `CouponExperimentService`로 분리합니다.

```java
public interface CouponExperimentService {

    CreateCouponResponse create(CreateCouponRequest request);

    CouponStatusResponse getStatus(Long couponId);

    CouponStatusResponse reset(Long couponId);
}
```

- `create`: 실험용 쿠폰과 초기 재고 생성
- `getStatus`: 현재 재고와 발급 건수 조회
- `reset`: 발급 이력을 삭제하고 재고를 초기 상태로 복구

이 기능을 발급 Service와 분리해 실험 준비와 상태 확인 로직이 각 전략에 반복되지 않도록 했습니다.

---

## 공통 예외 처리

공통 예외 구조는 `global.exception` 패키지에서 관리합니다.

```text
BaseErrorCode
├── CommonErrorCode
└── ExperimentErrorCode

GeneralException
ErrorResponse
GlobalExceptionHandler
```

`BaseErrorCode`는 HTTP 상태, 오류 코드, 메시지의 공통 규격입니다.

```java
public interface BaseErrorCode {
    HttpStatus getStatus();
    String getCode();
    String getMessage();
}
```

`GlobalExceptionHandler`는 `@RestControllerAdvice`를 이용해 experiment 모듈의 예외 응답을 한 곳에서 처리합니다.

```text
GeneralException
    → ErrorCode에 정의된 HTTP status와 ErrorResponse

Validation Exception
    → 필드별 validation 오류

Unhandled Exception
    → INTERNAL_SERVER_ERROR
```

공통 오류 응답은 다음 형식을 사용합니다.

```json
{
  "code": "EXPERIMENT409-0",
  "message": "쿠폰 재고가 소진되었습니다.",
  "data": null
}
```

---

## 새로운 전략 추가하기

새로운 발급 전략은 기존 Controller에 분기문을 추가하는 대신 `CouponIssueService` 구현체를 추가하는 방식으로 확장합니다.

```java
@Service
public class NewCouponIssueServiceImpl implements CouponIssueService {

    @Override
    public CouponIssueResponse issue(
            Long couponId,
            CouponIssueRequest request) {

        // 전략별 발급 로직
        return CouponIssueResponse.success(couponId, request);
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.NEW_STRATEGY;
    }
}
```

필요한 경우 `CouponIssueStrategy`에 값을 추가하고, 전략별 쿼리가 필요하면 Repository에 메서드를 추가합니다.

Spring Bean으로 등록되면 Resolver가 구현체를 자동으로 수집하므로 `/issue` endpoint의 Controller 코드는 변경하지 않습니다.

```text
Service 구현
    ↓
Spring Bean 등록
    ↓
supports()로 전략 식별
    ↓
Resolver 등록
    ↓
공통 /issue API에서 호출
```

---

## 테스트

공통 전략 선택 로직은 `CouponIssueServiceResolverTest`에서 다음 내용을 확인합니다.

- 요청한 전략의 Service를 반환하는지
- 지원하지 않는 전략을 거부하는지
- 동일 전략의 Service가 중복 등록되는 것을 거부하는지

공통 재고 Entity의 기본 동작은 `CouponStockTest`에서 확인합니다.

전략별 동시성 검증과 성능 측정은 별도 문서에서 다룹니다.

- [동시성 실험 설계](./concurrency-experiment.md)
- [동시성 실험 결과](./experiment-results.md)

---

## 정리

공통 코드에서는 전략이 달라도 동일해야 하는 부분을 고정합니다.

```text
공통
- API
- 요청/응답 DTO
- Entity
- 실험 관리 기능
- 전략 선택 방식
- 예외 응답 구조

전략별 구현
- 재고 확보 방식
- 필요한 Repository 쿼리
- 트랜잭션과 동시성 제어 로직
```

이를 통해 각 전략을 독립적으로 구현하면서도 같은 인터페이스와 데이터 조건에서 실험할 수 있습니다.
