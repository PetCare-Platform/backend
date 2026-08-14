-- ============================================================
-- 쿠폰 발급 동시성 제어 테스트 스키마
-- (비관적 락 / DB 직접 삽입 / 낙관적 락 / 조건부 UPDATE / Redis 비교용)
--
-- 담당 구성: 1번 담당자가 비관적 락 + DB 직접 삽입 2개 진행, 2번 낙관적 락,
--           3번 조건부 UPDATE, 4번 Redis → 시나리오는 총 5개
--
-- 팀 ERD(종합프로젝트.png) 기준, Kafka와 무관한 테이블만 발췌:
--   포함: app_user, coupon, coupon_stock, coupon_issue
--   제외: event, event_status_history           (이벤트 라이프사이클, 이번 범위 아님)
--         notification_log, issue_message        (Kafka 붙인 뒤 필요)
--         idempotency_key                        (메시지 재처리 멱등성용, 이번 범위 아님)
--         reconciliation_report, verification_detail (Redis-DB 정합성 대조 리포트, 필요 시 추가)
--         coupon_issue_history                   (상태 전이 감사 로그, 이번 비교엔 불필요)
-- 컬럼도 이번 테스트와 무관한 건 뺐음 (discount_type 등 쿠폰 비즈니스 필드, event_id FK,
-- sequence_no — Redis 시나리오에서 필요해지면 그때 추가)
--
-- 대상 DB: MySQL 8.0+
-- 규모: 회원 20,000명 / 쿠폰당 재고 100장, 시나리오별 쿠폰 5개
-- ============================================================

DROP TABLE IF EXISTS issue_attempt_log;
DROP TABLE IF EXISTS coupon_issue;
DROP TABLE IF EXISTS coupon_stock;
DROP TABLE IF EXISTS coupon;
DROP TABLE IF EXISTS app_user;

-- ------------------------------------------------------------
-- 1. app_user (ERD 그대로, 가입 로직 없이 더미로만 채움)
-- ------------------------------------------------------------
CREATE TABLE app_user (
    user_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_id    VARCHAR(50) NOT NULL,
    name        VARCHAR(50) NOT NULL,
    email       VARCHAR(255) NOT NULL,   -- 로그 출력 시 마스킹 처리
    phone       VARCHAR(20) NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- ------------------------------------------------------------
-- 2. coupon (event_id, discount 관련 필드 등 이번 테스트와 무관한 컬럼 제외)
-- ------------------------------------------------------------
CREATE TABLE coupon (
    coupon_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    issue_start_at   DATETIME(6) NOT NULL,
    issue_end_at     DATETIME(6) NOT NULL,
    limit_per_member INT NOT NULL DEFAULT 1,   -- 1인 최대 발급 수
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- ------------------------------------------------------------
-- 3. coupon_stock (ERD 그대로 — 락 대상을 coupon과 분리)
-- ------------------------------------------------------------
CREATE TABLE coupon_stock (
    coupon_id          BIGINT PRIMARY KEY,
    total_quantity     INT NOT NULL,
    issued_quantity    INT NOT NULL DEFAULT 0,
    remaining_quantity INT NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,  -- 낙관적 락용 (다른 시나리오는 무시하면 됨)
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stock_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(coupon_id)
);

-- ------------------------------------------------------------
-- 4. coupon_issue (ERD 기준, sequence_no·coupon_code는 지금 안 쓰므로 제외
--    — Redis 시나리오에서 필요해지면 컬럼만 추가하면 됨)
-- ------------------------------------------------------------
CREATE TABLE coupon_issue (
    coupon_issue_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id       BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    request_id      VARCHAR(64) NOT NULL,        -- 멱등키 (재시도 중복 방지)
    status          VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    issued_at       DATETIME(6) NOT NULL,
    used_at         DATETIME(6) NULL,
    canceled_at     DATETIME(6) NULL,
    failed_at       DATETIME(6) NULL,
    fail_reason     VARCHAR(200) NULL,
    expires_at      DATETIME(6) NULL,
    version         BIGINT NOT NULL DEFAULT 0,    -- 상태 전이 동시성 제어용
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- ERD엔 없지만 초과/중복 발급 검증의 기준점으로 추가
    CONSTRAINT uq_coupon_user UNIQUE (coupon_id, user_id),
    CONSTRAINT uq_request_id UNIQUE (request_id),
    CONSTRAINT fk_issue_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(coupon_id),
    CONSTRAINT fk_issue_user FOREIGN KEY (user_id) REFERENCES app_user(user_id)
);

-- ------------------------------------------------------------
-- 5. 부하테스트 시도 로그 (ERD엔 없는 테스트 전용 테이블)
--    시나리오 구분은 별도 컬럼 대신 coupon_id로 한다 — 시나리오별로 쿠폰 row를
--    분리해서 생성하고, coupon.name으로 구분/JOIN
-- ------------------------------------------------------------
CREATE TABLE issue_attempt_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id    BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    result       VARCHAR(20) NOT NULL,   -- SUCCESS / FAIL_SOLD_OUT / FAIL_DUPLICATE / FAIL_LOCK_TIMEOUT / FAIL_ERROR
    attempted_at DATETIME(3) NOT NULL,
    latency_ms   INT NOT NULL
);

CREATE INDEX idx_log_coupon ON issue_attempt_log (coupon_id, attempted_at);

-- ============================================================
-- 더미 데이터: 회원 20,000명
-- ============================================================
INSERT INTO app_user (login_id, name, email)
WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT
        ones.d
        + tens.d * 10
        + hundreds.d * 100
        + thousands.d * 1000
        + ten_thousands.d * 10000
        + 1 AS n
    FROM digits ones
    CROSS JOIN digits tens
    CROSS JOIN digits hundreds
    CROSS JOIN digits thousands
    CROSS JOIN digits ten_thousands
)
SELECT
    CONCAT('user', n),
    CONCAT('테스트회원', n),
    CONCAT('user', n, '@test.com')
FROM seq
WHERE n <= 20000
ORDER BY n;

-- 회원 더미 데이터 생성 결과 확인
SELECT
    COUNT(*) AS user_count,
    MIN(user_id) AS min_user_id,
    MAX(user_id) AS max_user_id
FROM app_user;

-- ============================================================
-- 더미 데이터: 시나리오별 쿠폰 5개 (각 재고 100장, 회원 20,000명 공통 사용)
-- 1번 담당자는 비관적락/DB직접삽입 두 coupon_id로 모두 테스트
-- ============================================================
INSERT INTO coupon (name, issue_start_at, issue_end_at, limit_per_member, status) VALUES
    ('비관적락',     NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'OPEN'),
    ('DB직접삽입',   NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'OPEN'),
    ('낙관적락',     NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'OPEN'),
    ('조건부UPDATE', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'OPEN'),
    ('Redis',        NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'OPEN');

INSERT INTO coupon_stock (coupon_id, total_quantity, issued_quantity, remaining_quantity, version)
SELECT coupon_id, 100, 0, 100, 0 FROM coupon;

-- ============================================================
-- 검증 쿼리 (테스트 실행 후 확인용)
-- ============================================================
-- 시나리오별(coupon.name) 초과 발급 여부 (0행이어야 정상)
-- SELECT c.name, COUNT(*) AS issued_cnt, s.total_quantity
-- FROM coupon_issue ci
-- JOIN coupon c ON c.coupon_id = ci.coupon_id
-- JOIN coupon_stock s ON s.coupon_id = ci.coupon_id
-- WHERE ci.status = 'ISSUED'
-- GROUP BY c.name, s.total_quantity
-- HAVING issued_cnt > s.total_quantity;

-- 중복 발급 여부 (0행이어야 정상)
-- SELECT coupon_id, user_id, COUNT(*)
-- FROM coupon_issue
-- GROUP BY coupon_id, user_id
-- HAVING COUNT(*) > 1;

-- 재고 정합성 (0행이어야 정상)
-- SELECT c.name, s.remaining_quantity,
--        (s.total_quantity - COUNT(ci.coupon_issue_id)) AS expected_remaining
-- FROM coupon_stock s
-- JOIN coupon c ON c.coupon_id = s.coupon_id
-- LEFT JOIN coupon_issue ci ON ci.coupon_id = s.coupon_id AND ci.status = 'ISSUED'
-- GROUP BY c.name, s.remaining_quantity, s.total_quantity;

-- 시나리오별 성능 비교 (평균/최대 지연시간, 성공률)
-- SELECT c.name,
--        COUNT(*) AS total_attempts,
--        SUM(l.result = 'SUCCESS') AS success_cnt,
--        AVG(l.latency_ms) AS avg_latency_ms,
--        MAX(l.latency_ms) AS max_latency_ms
-- FROM issue_attempt_log l
-- JOIN coupon c ON c.coupon_id = l.coupon_id
-- GROUP BY c.name;
