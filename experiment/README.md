# Coupon Concurrency Experiment

쿠폰 발급 시 발생할 수 있는 동시성 문제를 여러 방식으로 구현하고 비교하기 위한
Spring Boot 실험 모듈입니다.

각 발급 방식은 동일한 요청/응답 구조를 사용하며,
동시성 제어 로직만 전략별 Service로 분리합니다.


## Tech Stack

- Java 21
- Spring Boot
- Spring Data JPA
- MySQL
- Redis
- Gradle


## Project Structure

```text
experiment
└── src/main/java/com/mycom/petcoupon/experiment
    ├── coupon/
    │   ├── controller/
    │   ├── dto/
    │   ├── entity/
    │   ├── repository/
    │   └── service/
    │
    ├── issue/
    │   ├── entity/
    │   └── repository/
    │
    ├── user/
    │   ├── entity/
    │   └── repository/
    │
    └── global/
        └── exception/
```

## 비교 전략

- [DIRECT](docs/strategies/direct.md)
- [PESSIMISTIC](docs/strategies/pessimistic.md)
- [OPTIMISTIC](docs/strategies/optimistic.md)
- [CONDITIONAL](docs/strategies/conditional.md)
- [REDIS](docs/strategies/redis.md)

## 문서

- [동시성 실험 설계](docs/concurrency-experiment.md)
- [실험 공통 코드 구조](docs/common-code.md)
- [동시성 실험 결과](docs/experiment-results.md)

## Observability

- [Grafana Dashboard]
- [k6 실험 시나리오]
