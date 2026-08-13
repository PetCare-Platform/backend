# Kafka ↔ Redis 협의 사항

Kafka 쪽 작업하면서 Redis 담당자와 맞춰봐야 할 부분 정리.

## 1. `global/config` 패키지 공유

- `KafkaConfig.java`가 이미 `global/config/` 밑에 들어가 있음
- 계획대로 `RedisConfig.java`도 같은 위치에 추가하면 됨, 충돌 없음

## 2. Kafka가 Redis에 의존하는 부분 생김

`kafka/consumer/CouponIssueEventRecoverer.java`가 메시지 처리 최종 실패 시 `StringRedisTemplate`을 직접 주입받아서 씁니다.

```java
private final StringRedisTemplate redisTemplate;
...
redisTemplate.opsForValue().increment("kafka:coupon-issue-event:fail-count");
```

**확인/협의 필요한 것:**
- 지금은 `RedisConfig.java`가 없어서 Spring Boot가 자동 구성해주는 기본 `StringRedisTemplate` 빈을 그대로 쓰고 있음
- `RedisConfig.java` 작성하실 때 `StringRedisTemplate` 빈 자체를 커스텀하게 재정의(예: 직렬화 방식 변경)하실 경우, 이 빈이 없어지거나 타입이 바뀌면 Kafka 쪽 코드가 깨질 수 있음 → `RedisTemplate<String, Object>` 같은 걸 새로 만드시더라도 `StringRedisTemplate` 자체는 유지되게 부탁드립니다
- Redis 키 네이밍 컨벤션 따로 정하고 계시면 `kafka:coupon-issue-event:fail-count` 이 이름도 그 규칙에 맞춰 리네이밍 가능

## 3. 로컬 Redis 실행 환경 관련

- 지금 로컬 테스트는 각자 PC에 설치된 `redis-server.exe`를 수동 실행하는 방식으로 진행 중
- Kafka는 `docker-compose.yml`로 브로커를 관리하고 있는데, Redis도 같이 docker-compose에 포함시킬지 논의 필요 (팀원 전체가 매번 수동으로 Redis 켜는 것보다 나을 수 있음)

## 4. Actuator Health 관련 참고

- `spring-boot-starter-data-redis`가 있으면 Actuator가 자동으로 Redis 헬스체크를 붙임
- 로컬에 Redis 안 떠 있으면 `/actuator/health`가 통째로 503(DOWN)으로 나옴 (Kafka 등 다른 컴포넌트가 멀쩡해도 마찬가지)
- 다른 팀원들이 이 503 보고 "뭔가 고장났다"고 오해할 수 있어서, 필요하면 헬스체크 그룹 분리나 안내 공유해두는 게 좋을 것 같음
