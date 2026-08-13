# Kafka 파이프라인 구현 및 테스트 정리

`experiment` 모듈 기준, Kafka 기초 설정부터 Producer → Broker → Consumer 파이프라인 검증, 장애 대응(재시도/복구)까지 진행한 기록입니다.

## 1. 구현 내용

### 1-1. 의존성 (`experiment/build.gradle`)

```gradle
implementation 'org.springframework.kafka:spring-kafka'
implementation 'com.fasterxml.jackson.core:jackson-databind'
implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
```

- `spring-kafka`: Kafka 연동 기본
- `jackson-databind` (legacy): Spring Boot 4.1.0은 Jackson 3(`tools.jackson.*`)을 기본으로 쓰지만, `spring-kafka`의 `JsonSerializer`/`JsonDeserializer`는 아직 Jackson 2(`com.fasterxml.jackson.*`) API를 참조해서 명시적으로 추가 필요
- `jackson-datatype-jsr310`: `LocalDateTime` 등 Java 8 날짜/시간 타입 직렬화 지원

### 1-2. 로컬 브로커 (`experiment/docker-compose.yml`)

Kafka 단일 브로커, KRaft 모드(Zookeeper 없음), 이미지는 `apache/kafka:3.7.0` 사용.

```
docker compose up -d
```

### 1-3. 패키지 구조

```
com.mycom.petcoupon.experiment
├── global/config/
│   └── KafkaConfig.java                    # KafkaAdmin, NewTopic, ProducerFactory, KafkaTemplate,
│                                            # ConsumerFactory(+ErrorHandlingDeserializer),
│                                            # ConcurrentKafkaListenerContainerFactory(+재시도/에러핸들러) 빈
├── kafka/
│   ├── constant/KafkaTopics.java           # 토픽 이름 상수
│   ├── dto/CouponIssueEvent.java           # 메시지 payload (record)
│   ├── producer/CouponIssueEventProducer.java
│   └── consumer/
│       ├── CouponIssueEventConsumer.java   # 정상 메시지 처리
│       └── CouponIssueEventRecoverer.java  # 재시도 소진 후 최종 실패 처리 (log + Redis INCR)
```

- Redis 담당자가 `global/config/RedisConfig.java`로 진행하기로 해서, Kafka 설정도 같은 위치(`global/config`)에 통합
- Producer/Consumer/DTO/토픽 상수/Recoverer는 도메인 성격이 강해 `kafka/` 패키지 유지 (Config는 순수 인프라 배선만 담당)

### 1-4. 장애 대응: 재시도 + 최종 실패 처리

- 역직렬화 실패 시 `ErrorHandlingDeserializer`로 감싸서 예외를 컨테이너 에러 핸들러로 정상 위임
- `DefaultErrorHandler` + `FixedBackOff(1000L, 2L)`: 1초 간격으로 2회 재시도
- **주의**: 이 재시도는 **역직렬화 실패에는 적용되지 않습니다.** 같은 바이트는 재시도해도 항상 똑같이 실패하는 결정론적 오류라, Spring Kafka 프레임워크가 `DeserializationException`을 재시도 대상에서 자동 제외하고 바로 recoverer로 넘깁니다. 재시도가 실제로 적용되는 건 역직렬화는 성공했지만 **리스너 로직(비즈니스 처리) 중 예외**가 난 경우(DB 저장 실패 등 일시적 오류)입니다.
- 최종 실패 시(재시도 대상이든 즉시 recoverer로 넘어간 역직렬화 실패든) `CouponIssueEventRecoverer`가 `log.error`로 상세 원인(예외 스택트레이스 포함) 기록 + Redis 키 `kafka:coupon-issue-event:fail-count` INCR
- Redis 자체가 장애 상태라 INCR이 실패하는 경우까지 대비해서, Redis 호출은 try-catch로 감싸 별도 로그만 남기고 예외를 밖으로 던지지 않도록 처리 (recoverer가 예외를 던지면 Kafka가 해당 레코드를 "복구 실패"로 보고 offset이 안 넘어가서 같은 메시지가 무한 반복될 수 있음)
- 팀 논의 결과대로 "로그 → (해당 시) 재시도 → 최종 실패 시 로그+Redis 카운터" 방식으로 결정

### 1-5. `application.properties`

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
spring.kafka.consumer.group-id=petcoupon-experiment
```

- Producer/Consumer의 (역)직렬화, 재시도 정책 등 세부 설정은 `KafkaConfig.java` 코드에서 직접 관리 (환경마다 안 바뀌는 고정 선택이라 코드가 더 적합하다고 판단)
- `bootstrap-servers`만 환경별로 달라질 수 있어 `${ENV_VAR:기본값}` 패턴 유지

## 2. 테스트 순서

1. **브로커 기동**: `docker compose up -d` → `docker compose ps`로 확인
2. **앱 실행**: STS에서 `ExperimentApplication` Run As → Spring Boot App, 에러 없이 `Started ExperimentApplication` 확인
3. **토픽 자동 생성 확인**:
   ```
   docker exec -it petcoupon-kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
   ```
   `coupon-issue-events`가 수동 명령 없이 자동으로 생성되어 있어야 정상
4. **정상 메시지 테스트** (콘솔 프로듀서):
   ```
   docker exec -it petcoupon-kafka /opt/kafka/bin/kafka-console-producer.sh --topic coupon-issue-events --bootstrap-server localhost:9092
   ```
   ```json
   {"couponId":1,"userId":1,"requestId":"manual-test","issuedAt":"2026-08-13T12:00:00"}
   ```
   → 앱 콘솔에서 `[CouponIssueEvent] 수신: ...` 로그 확인
5. **깨진 메시지로 복구 테스트** (같은 프로듀서 세션에 이어서):
   ```
   this-is-not-valid-json
   ```
   → 역직렬화 실패라 재시도 없이 바로 `[CouponIssueEvent] 최종 처리 실패: partition=..., offset=...` 로그 확인 (재시도 로그는 안 뜨는 게 정상)
6. **Redis 카운터 확인**:
   ```
   redis-cli get kafka:coupon-issue-event:fail-count
   ```
   실패 건수만큼 값이 올라가 있으면 정상

## 3. 트러블슈팅 기록

진행하면서 겪은 문제와 해결 순서. 비슷한 상황 겪으면 참고.

| # | 증상 | 원인 | 해결 |
|---|---|---|---|
| 1 | `bitnami/kafka:3.7` 이미지 pull 실패 | Bitnami가 구버전 태그 대거 정리 | `apache/kafka:3.7.0` 공식 이미지로 교체 |
| 2 | 앱 기동 시 `KafkaTemplate` 빈을 찾을 수 없음 | Boot 자동 구성 `KafkaTemplate<Object,Object>`와 우리 코드가 요구하는 `KafkaTemplate<String, CouponIssueEvent>`가 제네릭 불일치(불변성)로 매칭 안 됨 | `KafkaConfig`에 타입 명시한 `ProducerFactory`/`KafkaTemplate` 빈 직접 정의 |
| 3 | `NewTopic` 빈을 등록했는데도 토픽이 브로커에 생성 안 됨 | 근본 원인은 #4/#5와 동일 — Boot의 Kafka 자동 구성이 이 환경에서 전혀 동작 안 해서 `KafkaAdmin` 빈 자체가 안 만들어짐 | `KafkaConfig`에 `KafkaAdmin` 빈 직접 정의 → 토픽 자동 생성 확인 완료 |
| 4 | Consumer 관련 로그가 전혀 안 찍힘, 컨슈머 그룹도 브로커에 안 잡힘 | `@KafkaListener`를 처리하는 빈 후처리기가 전혀 동작 안 함 (자동 구성 미작동) | `KafkaConfig`에 `@EnableKafka` 명시적으로 추가 |
| 5 | `@EnableKafka` 추가 후 `kafkaListenerContainerFactory` 빈을 찾을 수 없음 | Boot가 Consumer 쪽 자동 구성(`ConsumerFactory`, `ListenerContainerFactory`)을 이 환경에서 생성 안 해줌 | `KafkaConfig`에 `ConsumerFactory`/`ConcurrentKafkaListenerContainerFactory` 빈 직접 정의 (이름을 `kafkaListenerContainerFactory`로 맞춤) |
| 6 | 빌드 에러: `com.fasterxml.jackson.databind.JavaType cannot be resolved` | Spring Boot 4.1.0이 Jackson 3(`tools.jackson.*`)을 기본 채택하면서, Jackson 2 API를 쓰는 `spring-kafka`의 `JsonSerializer`/`JsonDeserializer`가 필요로 하는 클래스가 클래스패스에서 빠짐 | `com.fasterxml.jackson.core:jackson-databind` 명시적 추가 (버전은 Boot BOM이 관리, `2.21.4`로 해결) |
| 7 | 메시지 수신 시 `SerializationException` / `Java 8 date/time type LocalDateTime not supported` | `CouponIssueEvent.issuedAt`(`LocalDateTime`)을 Jackson이 기본 모듈만으로는 처리 못 함 | `jackson-datatype-jsr310` 의존성 추가 |
| 8 | Actuator `/actuator/health` 503 | 로컬에 Redis 서버가 안 떠 있어서 `RedisConnectionFailureException` 발생 (Kafka와 무관) | 로컬 Redis(`redis-server.exe`) 실행해서 해결 |
| 9 | 깨진 메시지 처리 시 무한 에러 반복 (`This error handler cannot process 'SerializationException's directly`) | `DefaultErrorHandler`가 원본 `SerializationException`을 직접 처리 못 함, 해당 offset에서 계속 재시도만 반복 | Consumer 쪽 key/value deserializer를 `ErrorHandlingDeserializer`로 감싸서 예외를 정상적으로 에러 핸들러에 위임되게 수정 |

## 4. 최종 결과

**정상 케이스**: 콘솔 프로듀서로 넣은 메시지가 Consumer에 정상 수신되고 offset까지 커밋되는 것 확인

```
[CouponIssueEvent] 수신: couponId=1, userId=1, requestId=manual-test-8
```

**장애 케이스**: 깨진 메시지 발행 시 재시도 후 최종 실패 로그와 함께 Redis 카운터 증가까지 확인

```
[CouponIssueEvent] 최종 처리 실패: partition=1, offset=1
org.springframework.kafka.support.serializer.DeserializationException: failed to deserialize
Caused by: ... JsonParseException: Unrecognized token 'this' ...
```

이후 무한 재시도 없이 다음 메시지 정상 처리로 이어짐 (컨슈머가 죽지 않고 계속 살아있는 것 확인).

## 5. 남은 작업

- [x] `NewTopic` 자동 생성 이슈 → `KafkaAdmin` 빈 추가로 해결
- [x] `ErrorHandlingDeserializer` + 재시도 + 최종 실패 처리(Redis INCR) 추가 완료
- [ ] 실제 쿠폰 발급 서비스(`CouponIssueService` 계열)에 `CouponIssueEventProducer` 연결 — 지금은 콘솔 프로듀서로만 검증한 상태, 실제 발급 흐름에는 아직 안 붙어 있음
- [ ] Redis 실패 카운터를 실제로 어떻게 소비/알림할지 (모니터링 대시보드 연동 등) 후속 논의 필요
