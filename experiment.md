# Coupon Concurrency Experiment

한정된 쿠폰 재고에 다수의 요청이 동시에 발생하는 상황에서
여러 동시성 제어 전략의 정합성과 성능을 비교하는 실험입니다.

## 비교 전략

- DIRECT
- PESSIMISTIC
- OPTIMISTIC
- CONDITIONAL
- REDIS

## 문서

- [동시성 실험 설계 및 결과]
- [실험 공통 코드 구조]

## Observability

- [Grafana Dashboard]
- [k6 실험 시나리오]
