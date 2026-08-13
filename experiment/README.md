# Coupon Concurrency Experiment

선착순 쿠폰 발급 과정에서 발생하는 동시성 문제를 확인하고,
여러 동시성 제어 방식을 동일한 조건에서 비교하기 위한 실험 모듈입니다.

동시성 실험의 설계, 공통 코드 구조, 실험 결과에 대한 상세 내용은
아래 문서에서 확인할 수 있습니다.

## 문서

- [동시성 실험 설계](docs/concurrency-experiment.md)
- [실험 공통 코드 구조](docs/common-code.md)
- [동시성 실험 결과](docs/experiment-results.md)


## Tech Stack

- Java 21
- Spring Boot 4.1.0
- Spring Data JPA
- MySQL
- Redis
- Gradle
- k6


## Requirements

실행 전 다음 환경이 필요합니다.

- Java 21
- MySQL
- Gradle Wrapper

부하 테스트를 수행하는 경우 추가로 k6가 필요합니다.

## 비교 전략

- [DIRECT](docs/strategies/direct.md)
- [PESSIMISTIC](docs/strategies/pessimistic.md)
- [OPTIMISTIC](docs/strategies/optimistic.md)
- [CONDITIONAL](docs/strategies/conditional.md)
- [REDIS](docs/strategies/redis.md)

## Observability

- [Grafana Dashboard]
- [k6 실험 시나리오]
