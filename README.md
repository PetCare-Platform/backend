# 🎁 Coupon Concurrency Experiment

선착순 쿠폰 발급 과정에서 발생하는 동시성 문제를 확인하고,
여러 동시성 제어 방식을 동일한 조건에서 비교하기 위한 실험 모듈입니다.

> **멘토링 질문:** [프로젝트 멘토링 질문](docs/mentoring-questions.md)

동시성 실험의 설계, 공통 코드 구조, 실험 결과에 대한 상세 내용은
아래 문서에서 확인할 수 있습니다.

## 📚 문서

- [동시성 실험 설계](docs/concurrency-experiment.md)
- [실험 공통 코드 구조](docs/common-code.md)
- [AWS 부하테스트 실행 절차](docs/aws-load-test-guide.md)
- [동시성 실험 결과](docs/experiment-results.md)
- [프로젝트 멘토링 질문](docs/mentoring-questions.md)


## 🛠 Tech Stack

### Application

![Java](https://img.shields.io/badge/Java_21-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)

### Data · Atomicity · Messaging

![MySQL](https://img.shields.io/badge/MySQL_8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_7.2-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Lua](https://img.shields.io/badge/Lua_Script-2C2D72?style=for-the-badge&logo=lua&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka_3.7-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)

### Build · Test · Environment

![Gradle](https://img.shields.io/badge/Gradle_9.5.1-02303A?style=for-the-badge&logo=gradle&logoColor=white)
![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=for-the-badge&logo=junit5&logoColor=white)
![Mockito](https://img.shields.io/badge/Mockito-78A641?style=for-the-badge)
![k6](https://img.shields.io/badge/k6-7D64FF?style=for-the-badge&logo=k6&logoColor=white)
![Docker Compose](https://img.shields.io/badge/Docker_Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)


## ✅ Requirements

실행 전 다음 환경이 필요합니다.

- Java 21
- MySQL
- Gradle Wrapper

부하 테스트를 수행하는 경우 추가로 k6가 필요합니다.

## 🧪 실험 배경

선착순 쿠폰 발급은 짧은 시간에 동일한 재고로 요청이 집중됩니다. 동시성 제어가 없으면 여러 요청이 같은 재고를 읽고 갱신하면서 초과 발급과 갱신 유실이 발생할 수 있고, 잠금으로 정합성을 보장하더라도 높은 경합에서 처리량과 응답 속도가 충분한지는 별도로 확인해야 합니다.

이를 감으로 결정하지 않고 다음 질문에 대한 근거를 만들기 위해 실험을 시작했습니다.

- 재고보다 많은 요청이 동시에 들어와도 초과·중복 발급을 막을 수 있는가?
- 정합성을 지키면서도 요청 규모가 커질 때 처리량과 응답 시간을 유지할 수 있는가?
- Redis로 재고를 선점하고 Kafka로 DB 저장을 비동기화하는 복잡도를 감수할 만한가?

## 🔬 실험 진행 방식

동일한 쿠폰 발급 기능을 아래 6개 전략으로 구현하고, AWS의 독립된 서버에서 k6로 동시 요청을 발생시켜 비교했습니다. 전략 외의 조건을 가능한 같게 유지하고 각 비교 조건을 최소 3회 반복했으며, 응답 성능만이 아니라 초과 발급·1인 1매·중복 요청·최종 재고 정합성을 함께 검증했습니다.

| 단계 | 비교 대상 | 재고 / 요청 | 확인 목적 |
| --- | --- | ---: | --- |
| 실험 A | `DIRECT`, `PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`, `REDIS` | 10/20 → 1,000/2,000 | 재고 차감 방식별 정합성·처리량·지연시간 비교 |
| 실험 B | `REDIS`, `KAFKA` | 10/20 → 1,000/2,000 | 동일한 Redis 선점 후 DB 동기 저장과 Kafka 비동기 저장 비교 |
| 최종 검증 | `KAFKA` | 10,000/20,000 | 초과 발급 없이 최종 요구 규모를 처리하는지 검증 |

상세한 환경, 시나리오, 판정 기준은 [동시성 실험 설계](docs/concurrency-experiment.md), 전체 측정값과 해석은 [동시성 실험 결과](docs/experiment-results.md)에서 확인할 수 있습니다.

## ⚙️ 비교 전략

- [DIRECT](docs/strategies/direct.md)
- [PESSIMISTIC](docs/strategies/pessimistic.md)
- [OPTIMISTIC](docs/strategies/optimistic.md)
- [CONDITIONAL](docs/strategies/conditional.md)
- [REDIS](docs/strategies/redis.md)
- [KAFKA](docs/strategies/kafka.md)

## 📊 실험 결과

### 재고 차감 전략 비교

공통 최대 구간(재고 1,000개, 요청 2,000건) 3회 평균 결과입니다. `DIRECT`는 동시성 제어가 없는 비교 기준으로, 초과 발급과 갱신 유실이 발생했습니다. 나머지 전략은 정합성을 모두 지켰지만 성능에서 차이가 났습니다.

| 전략 | 정합성 | 처리량 | p95 응답 시간 | 3초 기준 |
| --- | :---: | ---: | ---: | :---: |
| `DIRECT` | ✕ | 138 req/s | 12,988ms | ✕ |
| `PESSIMISTIC` | ○ | 227 req/s | 8,195ms | ✕ |
| `OPTIMISTIC` | ○ | 215 req/s | 8,798ms | ✕ |
| `CONDITIONAL` | ○ | 235 req/s | 8,005ms | ✕ |
| `REDIS` | ○ | **855 req/s** | **1,941ms** | ○ |

DB 전략은 재고 한 행에 대한 경합으로 220~235 req/s 근처에서 포화했습니다. Redis Lua Script는 재고 확인, 중복 검사, 차감을 메모리에서 원자적으로 처리해 DB 최선인 `CONDITIONAL`보다 처리량이 **3.6배** 높고 p95 응답 시간은 약 **1/4**로 줄었습니다.

### Redis 동기 저장과 Kafka 비동기 저장 비교

두 전략 모두 Redis Lua로 재고를 선점합니다. 차이는 같은 HTTP 요청에서 DB 발급 이력까지 저장하는지, Kafka Consumer가 나중에 저장하는지입니다.

| 지표 | `REDIS` | `KAFKA` | 해석 |
| --- | ---: | ---: | --- |
| 평균 접수 응답 | 1,262.9ms | **262.8ms** | Kafka가 **4.8배** 빠름 |
| p95 접수 응답 | 1,795.2ms | **492.6ms** | 두 전략 모두 3초 기준 충족 |
| DB 최종 반영 완료 | **2.28초** | 5.22초 | Kafka는 비동기 적체로 최종 완료가 느림 |
| 정합성 | ○ | ○ | 접수 수와 최종 DB 반영 수 일치 |

Kafka는 Redis 선점과 메시지 발행까지 마친 뒤 `WAITING`을 반환하여 DB 쓰기 지연을 사용자 응답에서 분리했습니다. 다만 빠른 접수 응답의 대가로 최종 완료 지연과 Consumer·재시도·보상·DLQ·결과 조회 API 같은 운영 복잡도가 추가됩니다.

### 최종 선정: Redis + Kafka

이 프로젝트에서는 **Redis로 재고를 원자적으로 선점하고 Kafka로 DB 저장을 비동기화하는 전략**을 선택했습니다.

Redis 단독은 DB 최종 반영 완료 시간이 2.28초로 Redis + Kafka의 5.22초보다 빨랐지만, 선착순 쿠폰 서비스에서는 최종 저장 완료 속도보다 **사용자가 체감하는 신청 접수 속도**를 더 중요하게 고려했습니다. Redis + Kafka는 평균 접수 응답을 1,262.9ms에서 262.8ms로 4.8배 단축했기 때문에, 최종 반영 지연과 운영 복잡도를 감수하고 최종 전략으로 선택했습니다.

- Redis는 DB 기반 전략과 같은 정합성을 유지하면서 높은 경합의 성능 병목을 크게 줄였습니다.
- Kafka는 신청 접수와 DB 저장을 분리해 공통 최대 구간의 평균 접수 응답을 4.8배 단축했습니다.
- 최종 규모(재고 10,000개, 요청 20,000건) 6회에서 매번 정확히 10,000건을 발급했고, 초과 발급과 중복 발급은 0건이었습니다.

> [!NOTE]
> Redis 단독도 요청 2,000건에서 3초 기준을 충족했으며, 요청 20,000건 규모에서 Kafka와 직접 비교하지는 않았습니다. 따라서 Kafka가 정합성을 위해 필수였다기보다, **선착순 서비스의 빠른 접수 응답을 위해 운영 복잡도와 최종 완료 지연을 감수한 선택**입니다.

## 📊 Observability

- [Grafana Dashboard]
- [k6 실험 시나리오]
