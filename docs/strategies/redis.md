# REDIS — Redis + Lua 원자적 재고 차감

이 문서는 Redis 기반 전략(`RedisCouponIssueServiceImpl`)의 동작 원리와 구현 방식을 설명합니다.

공통 API, DTO, 예외 구조는 [실험 공통 코드 구조](../common-code.md)를 따릅니다.

---

## 원리

`PESSIMISTIC` / `OPTIMISTIC` / `CONDITIONAL`은 모두 DB 위에서 동시성을 제어하지만, `REDIS` 전략은 재고 차감과 중복 검사 자체를 **DB가 아닌 Redis**에서, 그것도 **Lua 스크립트 하나로 원자적으로** 처리합니다.

Redis는 싱글 스레드로 명령을 처리하므로, 재고 확인 → 중복 확인 → 차감 → 중복 방지 키 등록까지의 과정을 Lua 스크립트 안에 넣으면 그 사이에 다른 요청이 끼어들 수 없습니다. 즉 별도의 락이나 버전 비교, 조건부 UPDATE 없이도 원자성이 보장됩니다.

```text
DB 기반 전략            → DB row-level 동시성 제어 (Lock / version / WHERE 조건)
REDIS 전략               → Redis 싱글 스레드 + Lua 스크립트 원자성
```

DB는 재고 차감이 확정된 이후, 발급 이력(`CouponIssue`)을 영속화하는 역할만 담당합니다.

---

## 구성 요소

```text
coupon/redis/
└── RedisCouponStockServiceImpl.java   # Redis 키 관리, 스크립트 실행 진입점

coupon/service/
└── RedisCouponIssueServiceImpl.java   # 발급 흐름, 결과 코드 → 예외 매핑

global/config/
├── RedisConfig.java                   # StringRedisTemplate 빈 등록
└── RedisLuaConfig.java                # decreaseStockScript / restoreStockScript
```

---

## Redis Key 구조

| Key | 용도 | 예시 |
| --- | --- | --- |
| `coupon:stock:{couponId}` | 남은 재고 수량 | `coupon:stock:1` |
| `coupon:{couponId}:request:{requestId}` | requestId 중복 방지 | `coupon:1:request:abc-123` |
| `coupon:{couponId}:user:{userId}` | 동일 쿠폰에 대한 동일 사용자 중복 방지 | `coupon:1:user:5` |

```java
public String getKey(Long couponId) {
    return "coupon:stock:" + couponId;
}

public String getRequestKey(Long couponId, String requestId) {
    return "coupon:" + couponId + ":request:" + requestId;
}

public String getUserKey(Long couponId, Long userId) {
    return "coupon:" + couponId + ":user:" + userId;
}
```

DB `CouponStock`의 값과는 별개로 관리되는 **Redis 전용 재고 카운터**이므로, 실험을 시작하기 전 반드시 `initialize()`로 DB 재고 값을 Redis에 동기화해야 합니다.

```java
@Override
public void initialize(Long couponId) {

    CouponStock stock = couponStockRepository.findById(couponId)
            .orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));

    delete(couponId);

    redisTemplate.opsForValue().set(getKey(couponId), String.valueOf(stock.getRemainingQuantity()));
}
```

`initialize()`는 재고 키를 새로 세팅하기 전에 `delete()`로 해당 쿠폰과 관련된 재고/requestId/user 키를 모두 지워, 이전 실험에서 남은 중복 방지 키가 다음 실험에 영향을 주지 않도록 합니다.

```java
@Override
public void delete(Long couponId) {

    Set<String> keys = new HashSet<>();
    keys.add(getKey(couponId));

    Set<String> requestKeys = redisTemplate.keys("coupon:" + couponId + ":request:*");
    Set<String> userKeys = redisTemplate.keys("coupon:" + couponId + ":user:*");

    if (requestKeys != null) keys.addAll(requestKeys);
    if (userKeys != null) keys.addAll(userKeys);

    if (!keys.isEmpty()) {
        redisTemplate.delete(keys);
    }
}
```

---

## Lua 스크립트: 재고 차감

재고 확인, requestId 중복 확인, 동일 쿠폰에 대한 동일 사용자 중복 방지, 재고 차감, 중복 방지 키 등록을 **하나의 스크립트**로 묶어 원자적으로 실행합니다.

```lua
local stock = redis.call('GET', KEYS[1])

-- 재고 키가 존재하지 않는 경우
if not stock then
    return -1
end

-- requestId 중복 확인
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -3
end

-- 동일 쿠폰에 대한 동일 사용자 중복 확인
if redis.call('EXISTS', KEYS[3]) == 1 then
    return -4
end

-- 재고가 소진된 경우
if tonumber(stock) <= 0 then
    return -2
end

-- 재고 차감
redis.call('DECR', KEYS[1])

-- 중복 방지 키 등록
redis.call('SET', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], ARGV[2])

return 1
```

| KEYS / ARGV | 의미 |
| --- | --- |
| `KEYS[1]` | 재고 키 (`coupon:stock:{couponId}`) |
| `KEYS[2]` | requestId 중복 확인 키 |
| `KEYS[3]` | (couponId, userId) 중복 확인 키 |
| `ARGV[1]` | requestId 값 (등록용) |
| `ARGV[2]` | userId 값 (등록용) |

### 반환값 매핑

| 반환값 | 의미 | 매핑되는 에러코드 |
| --- | --- | --- |
| `1` | 차감 성공 | - (`SUCCESS`) |
| `-1` | 재고 키 없음 | `COUPON_NOT_FOUND` |
| `-2` | 재고 소진 | `SOLD_OUT` |
| `-3` | requestId 중복 | `DUPLICATE_REQUEST` |
| `-4` | 동일 사용자 중복 | `DUPLICATE_USER` |

`PESSIMISTIC` / `OPTIMISTIC` / `CONDITIONAL`과 달리, **중복 검사와 재고 차감이 같은 원자적 연산 안에서 함께 처리**된다는 점이 특징입니다. 다른 전략들은 DB 사전 조회(`existsByRequestId` 등)와 UNIQUE 제약이라는 2단계 방어를 쓰지만, Redis 전략은 Lua 스크립트 한 번으로 두 종류의 중복을 모두 걸러냅니다.

---

## Lua 스크립트: 재고 복구 (보상 트랜잭션)

Redis에서 재고 차감과 중복 키 등록에는 성공했지만, 이어지는 DB `CouponIssue` 저장이 실패하면 Redis 상태를 원상복구해야 합니다.

```lua
redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('DEL', KEYS[3])

return 1
```

복구 역시 Lua 스크립트로 수행하여 재고 증가와 중복 방지 키 삭제를 하나의 원자적 연산으로 처리합니다. Redis 자체에는 RDBMS의 트랜잭션 롤백 개념이 없으므로, 이 스크립트가 **애플리케이션 레벨의 보상 로직** 역할을 합니다.

---

## 처리 흐름

```java
@Override
public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {

    Long result = redisCouponStockService.decreaseStock(
            couponId, request.requestId(), request.userId());

    if (result == -1) throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
    if (result == -2) throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
    if (result == -3) throw new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
    if (result == -4) throw new GeneralException(ExperimentErrorCode.DUPLICATE_USER);

    try {
        couponIssueRepository.saveAndFlush(
                CouponIssue.builder()
                        .coupon(couponRepository.getReferenceById(couponId))
                        .user(userRepository.getReferenceById(request.userId()))
                        .requestId(request.requestId())
                        .build()
        );
    } catch (Exception e) {
        redisCouponStockService.restoreStock(couponId, request.requestId(), request.userId());
        throw e;
    }

    return CouponIssueResponse.builder()
            .couponId(couponId)
            .userId(request.userId())
            .requestId(request.requestId())
            .result(CouponIssueResult.SUCCESS)
            .build();
}
```

```text
요청
 ↓
Lua: decreaseStockScript 실행 (재고 확인 + 중복 확인 + 차감을 원자적으로)
 ↓
결과 코드 분기
 ├─ -1 → COUPON_NOT_FOUND
 ├─ -2 → SOLD_OUT
 ├─ -3 → DUPLICATE_REQUEST
 ├─ -4 → DUPLICATE_USER
 └─  1 → 계속 진행
 ↓
CouponIssue DB 저장 (saveAndFlush)
 ↓
저장 실패 시 → Lua: restoreStockScript 실행 (보상) → 예외 재throw
 ↓
저장 성공 시 → SUCCESS 응답
```

다른 전략들과 달리 **DB 트랜잭션(`@Transactional`)이 없습니다.** 재고 차감은 Redis에서, 이력 저장은 DB에서 각각 독립적으로 일어나는 두 단계이며, 둘 사이의 정합성은 실패 시 명시적으로 호출하는 `restoreStock()` 보상 로직으로 맞춥니다.

---

## 중복 방지

다른 전략들의 "사전 체크 + UNIQUE 제약"과 접근 방식이 다릅니다.

| 구분 | DB 기반 전략 (`PESSIMISTIC`/`OPTIMISTIC`/`CONDITIONAL`) | `REDIS` |
| --- | --- | --- |
| 1차 방어 | `existsByRequestId` / `existsByCoupon_IdAndUser_Id` 사전 조회 | Lua 스크립트 내 `EXISTS` 검사 |
| 2차 방어 | `CouponIssue` UNIQUE 제약 (`uq_request_id`, `uq_coupon_user`) | 동일하게 `CouponIssue` UNIQUE 제약이 최종 방어선 |
| 원자성 근거 | DB Lock / version / WHERE 조건 | Redis 싱글 스레드 실행 |

Redis에서 중복이 아니라고 판단되어 통과하더라도, 최종적으로 `CouponIssue` 저장 시 DB UNIQUE 제약을 위반하면 트랜잭션이 실패하고 Redis 보상 로직이 실행됩니다. 즉 Redis 단계의 중복 방지가 Redis 자체 재시작, 키 유실, 다중 Redis 인스턴스 등으로 느슨해지더라도 DB UNIQUE 제약이 최후의 안전장치로 남아 있습니다.

---

## 장단점

### 장점

- 재고 차감, 중복 검사가 모두 인메모리에서 일어나므로 DB Lock 대비 처리 속도가 빠름
- DB 커넥션을 점유하는 시간이 짧음 (재고 차감 단계는 DB에 접근하지 않음)
- Lua 스크립트 하나로 원자성을 보장하므로 별도의 락/버전/재시도 로직이 필요 없음

### 단점

- Redis 재고와 DB 재고가 별도로 관리되므로 두 값이 어긋날 가능성이 있음(`initialize()` 누락, Redis 장애/재시작, AOF 미사용(`appendonly no`)으로 인한 데이터 유실 등)
- 재고 차감(Redis)과 이력 저장(DB)이 하나의 트랜잭션으로 묶여 있지 않아, 저장 실패 시 `restoreStock()` 보상 로직이 반드시 성공해야 정합성이 유지됨(보상 자체가 실패하는 경우는 별도 대응 필요)
- Redis 장애 시 발급 자체가 불가능해지는 새로운 단일 장애 지점(SPOF)이 생김

---

## API

### 1. 발급

공통 발급 API에서 `REDIS` 전략을 지정합니다.

```http
POST /experiment/coupons/{couponId}/issue?strategy=REDIS
```

Request:

```json
{
  "userId": 1,
  "requestId": "redis-request-001"
}
```

### 2. Redis 재고 초기화

실험을 시작하기 전, DB의 `CouponStock.remainingQuantity`를 기준으로 Redis 재고 키를 세팅합니다. 앞서 설명한 `initialize()` / `delete()`가 여기서 호출되며, 기존에 남아 있던 재고·requestId·user 키를 모두 지운 뒤 DB 값으로 다시 채웁니다.

```http
POST /experiment/coupons/{couponId}/redis/init
```

```java
@PostMapping("/coupons/{couponId}/redis/init")
public void initializeRedis(@PathVariable("couponId") Long couponId) {
    redisCouponStockService.initialize(couponId);
}
```

다른 전략과 달리 `REDIS` 전략은 이 초기화 호출 없이는 재고 키 자체가 없는 상태입니다. 
따라서 발급 API 호출 시 `decreaseStockScript`가 `-1`(`COUPON_NOT_FOUND`)을 반환하게 됩니다. 따라서 **동시성 테스트를 반복 실행할 때마다 매번 이 엔드포인트를 먼저 호출**해야 이전 실행의 잔여 상태가 섞이지 않습니다.

### 3. Redis 재고 조회

Redis에 현재 저장된 재고 값을 그대로 조회합니다. DB `CouponStock`을 거치지 않고 Redis `GET`만 수행하므로, 실험 도중 재고 소진 시점을 빠르게 확인하는 용도로 사용합니다.

```http
GET /experiment/coupons/{couponId}/redis-stock
```

```java
@GetMapping("/coupons/{couponId}/redis-stock")
public Long getRedisStock(@PathVariable("couponId") Long couponId) {
    return redisCouponStockService.getRemainingStock(couponId);
}
```

응답은 남은 재고 수량(`Long`)이며, 초기화가 안 된 경우 키가 없어 `null`이 반환될 수 있습니다(`getRemainingStock` 참고).

```text
POST /coupons/{couponId}/issue?strategy=REDIS   → 재고 차감 + 발급
POST /coupons/{couponId}/redis/init             → Redis 재고/키 초기화 (실험 전 필수)
GET  /coupons/{couponId}/redis-stock            → 현재 Redis 재고 조회 (모니터링용)
```

---

## What to check

Redis 전략에서는 다음 항목을 확인합니다.

```text
- decreaseStockScript가 재고 확인 + 중복 확인 + 차감을 한 번에 원자적으로 처리하는가
- 동시 요청에서 재고보다 많이 발급되지 않는가
- 동일 requestId 동시 요청 시 한 번만 성공하는가
- 동일 사용자 동시 요청 시 한 번만 성공하는가
- DB 저장 실패 시 restoreStock()으로 Redis 재고/키가 정확히 복구되는가
- initialize() 호출 시 이전 실험의 request/user 키가 모두 삭제되는가
- Redis 재고와 DB CouponIssue 건수가 최종적으로 일치하는가
```

기본 시나리오(재고 3, 동시 요청 20건) 기준 기대 상태:

```text
SUCCESS = 3
SOLD_OUT = 17

issueCount = 3
remaining Redis stock = 0
```

---

## Related files

```text
coupon/
├── entity/
│   └── CouponStock.java
├── repository/
│   └── CouponStockRepository.java
├── redis/
│   ├── RedisCouponStockService.java
│   └── RedisCouponStockServiceImpl.java
└── service/
    └── RedisCouponIssueServiceImpl.java

issue/
├── entity/
│   └── CouponIssue.java
└── repository/
    └── CouponIssueRepository.java

global/
└── config/
    ├── RedisConfig.java
    └── RedisLuaConfig.java
```

현재 실험 환경에서는 Redis의 성능 측정에 집중하기 위해 AOF를 비활성화하여 `appendonly no`로 설정했습니다.
따라서 Redis 컨테이너가 재시작되면 메모리에 저장된 재고 및 중복 방지 키가 유실될 수 있습니다.
이는 운영 환경의 영속성 보장을 위한 설정이 아니라, Redis의 인메모리 재고 차감 성능과 동시성 제어 성능을 비교하기 위한 실험 환경의 설정입니다.

```yaml
redis:
  image: redis:7.2-alpine
  container_name: petcoupon-redis
  ports:
    - "6379:6379"
  command: redis-server --appendonly no
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 20
```
---

## 관련 문서

- [동시성 실험 설계](../concurrency-experiment.md)
- [실험 공통 코드 구조](../common-code.md)
- [PESSIMISTIC](./pessimistic.md)
- [OPTIMISTIC](./optimistic.md)
- [CONDITIONAL](./conditional.md)
- [동시성 실험 결과](../experiment-results.md)