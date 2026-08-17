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

## ⚙️ 비교 전략

- [DIRECT](docs/strategies/direct.md)
- [PESSIMISTIC](docs/strategies/pessimistic.md)
- [OPTIMISTIC](docs/strategies/optimistic.md)
- [CONDITIONAL](docs/strategies/conditional.md)
- [REDIS](docs/strategies/redis.md)
- [KAFKA](docs/strategies/kafka.md)

## 📊 Observability

- [Grafana Dashboard]
- [k6 실험 시나리오]
