import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

/**
 * 설계 문서 §7 중복·멱등성 시나리오 전용 스크립트.
 *
 * 성능 비교용 common.js는 요청마다 고유한 userId·requestId를 만들기 때문에
 * 중복 검사 경로를 탈 수 없다. 이 스크립트는 반대로 값을 고정해서 보낸다.
 *
 *   SCENARIO=same-user      같은 userId, 서로 다른 requestId  → 1인 1매 규칙 검증
 *   SCENARIO=same-request   같은 userId, 같은 requestId       → 멱등성 검증
 *
 * 실행 예:
 *   k6 run -e BASE_URL=http://<앱서버>:8080 -e COUPON_ID=27 \
 *          -e STRATEGY=REDIS -e SCENARIO=same-user -e RUN_ID=aws-dup-1 \
 *          load-test/k6/duplicate.js
 */

const SCENARIOS = ['same-user', 'same-request'];
const STRATEGIES = [
  'DIRECT',
  'PESSIMISTIC',
  'OPTIMISTIC',
  'CONDITIONAL',
  'REDIS',
  'KAFKA',
];

// 재고 저장소가 Redis인 전략은 setup에서 Redis 초기화가 필요하다.
const REDIS_STOCK_STRATEGIES = ['REDIS', 'KAFKA'];

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID;
const strategy = (__ENV.STRATEGY || '').toUpperCase();
const scenario = __ENV.SCENARIO || 'same-user';
const targetUserId = Number(__ENV.TARGET_USER_ID || 1);
const vus = Number(__ENV.VUS || 10);
const iterations = Number(__ENV.ITERATIONS || 10);
const runId = __ENV.RUN_ID || 'local-dup';
const kafkaWaitTimeout = Number(__ENV.KAFKA_WAIT_TIMEOUT || 30);
const kafkaPollInterval = Number(__ENV.KAFKA_POLL_INTERVAL || 1);

const resultFile =
  __ENV.RESULT_FILE ||
  `load-test/results/dup-${strategy.toLowerCase()}-${scenario}-${runId}.json`;

const issueSuccess = new Counter('dup_issue_success');
const duplicateUser = new Counter('dup_duplicate_user');
const duplicateRequest = new Counter('dup_duplicate_request');
const soldOut = new Counter('dup_sold_out');
const systemError = new Counter('dup_system_error');
const unexpectedResponse = new Counter('dup_unexpected_response');

const systemErrorRate = new Rate('dup_system_error_rate');
// 발급이 정확히 1건인지. 이 시나리오의 핵심 판정 기준이다.
const singleIssueRate = new Rate('dup_single_issue_rate');

// 409는 중복·매진을 나타내는 정상 비즈니스 응답이다.
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  scenarios: {
    duplicateIssue: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    dup_system_error_rate: ['rate==0'],
    dup_single_issue_rate: ['rate==1'],
    'http_req_duration{name:coupon_issue}': ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // KAFKA는 teardown에서 Consumer 처리 완료를 기다린다.
  teardownTimeout: `${kafkaWaitTimeout + 60}s`,
};

export function setup() {
  validateEnvironment();

  const resetResponse = http.post(
    `${baseUrl}/experiment/coupons/${couponId}/reset`,
    null,
    { tags: { name: 'coupon_reset' } },
  );

  if (resetResponse.status !== 200) {
    fail(
      `Coupon reset failed: status=${resetResponse.status}, body=${resetResponse.body}`,
    );
  }

  if (REDIS_STOCK_STRATEGIES.includes(strategy)) {
    const redisInitResponse = http.post(
      `${baseUrl}/experiment/coupons/${couponId}/redis/init`,
      null,
      { tags: { name: 'redis_init' } },
    );

    if (redisInitResponse.status !== 200) {
      fail(
        `Redis initialization failed: status=${redisInitResponse.status}, body=${redisInitResponse.body}`,
      );
    }
  }

  const body = parseJson(resetResponse);

  if (body === null || !Number.isInteger(body.totalQuantity)) {
    fail(`Invalid coupon reset response: body=${resetResponse.body}`);
  }

  // 재고가 요청 수보다 적으면 중복이 아니라 매진으로 막힐 수 있어 판정이 흐려진다.
  if (body.totalQuantity < iterations) {
    fail(
      `Coupon stock(${body.totalQuantity}) is smaller than iterations(${iterations}). ` +
        'Use a coupon with enough stock so that rejections come from duplication only.',
    );
  }

  return { couponId, totalQuantity: body.totalQuantity };
}

export default function run() {
  const sequence = exec.scenario.iterationInTest;

  // same-request: 모든 요청이 완전히 동일하다 (멱등성)
  // same-user   : 사용자만 같고 요청은 서로 다르다 (1인 1매)
  const requestId =
    scenario === 'same-request'
      ? buildRequestId('fixed')
      : buildRequestId(sequence);

  const response = http.post(
    `${baseUrl}/experiment/coupons/${couponId}/issue?strategy=${strategy}`,
    JSON.stringify({ userId: targetUserId, requestId }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'coupon_issue' },
    },
  );

  classifyResponse(response);
}

export function teardown(data) {
  let response;

  if (strategy === 'KAFKA') {
    // WAITING은 접수일 뿐이므로 Consumer의 DB 저장 완료를 기다린다.
    for (
      let elapsed = 0;
      elapsed < kafkaWaitTimeout;
      elapsed += kafkaPollInterval
    ) {
      response = getCouponStatus(data.couponId);
      const body = parseJson(response);

      if (body !== null && body.consistent === true && body.issueCount === 1) {
        break;
      }

      sleep(kafkaPollInterval);
    }
  } else {
    response = getCouponStatus(data.couponId);
  }

  const body = parseJson(response);
  const issuedExactlyOnce = body !== null && body.issueCount === 1;

  check(response, {
    'coupon status lookup succeeds': (res) => res.status === 200,
    'exactly one issue is persisted': () => issuedExactlyOnce,
    'stock is consistent': () => body !== null && body.consistent === true,
    'remaining stock is not negative': () => {
      if (body === null) return false;

      const remaining = REDIS_STOCK_STRATEGIES.includes(strategy)
        ? body.redisRemainingQuantity
        : body.dbRemainingQuantity;

      return remaining !== null && remaining !== undefined && remaining >= 0;
    },
  });

  singleIssueRate.add(issuedExactlyOnce);

  console.log(
    `[${strategy}/${scenario}] final status: ${response ? response.body : 'no response'}`,
  );
}

export function handleSummary(data) {
  return {
    [resultFile]: JSON.stringify(data, null, 2),
    stdout:
      `\n${strategy} / ${scenario} duplicate test completed.\n` +
      `Result file: ${resultFile}\n`,
  };
}

function getCouponStatus(targetCouponId) {
  return http.get(
    `${baseUrl}/experiment/coupons/${targetCouponId}/status`,
    { tags: { name: 'coupon_status' } },
  );
}

function buildRequestId(suffix) {
  const requestId = `dup-${strategy.toLowerCase()}-${scenario}-${runId}-${suffix}`;

  if (requestId.length > 64) {
    fail(
      `Generated requestId exceeds 64 characters: ${requestId}. Use a shorter RUN_ID.`,
    );
  }

  return requestId;
}

function classifyResponse(response) {
  if (response.status === 200) {
    const body = parseJson(response);
    // KAFKA는 비동기 접수라 SUCCESS가 아니라 WAITING을 반환한다.
    const expectedResult = strategy === 'KAFKA' ? 'WAITING' : 'SUCCESS';

    if (body !== null && body.result === expectedResult) {
      issueSuccess.add(1);
      systemErrorRate.add(false);
    } else {
      unexpectedResponse.add(1);
      systemErrorRate.add(true);
    }
    return;
  }

  if (response.status === 409) {
    const code = findErrorCode(parseJson(response));

    if (code === 'EXPERIMENT409-1') {
      duplicateRequest.add(1);
    } else if (code === 'EXPERIMENT409-2') {
      duplicateUser.add(1);
    } else if (code === 'EXPERIMENT409-0') {
      // 재고를 넉넉히 잡았으므로 매진이 나오면 설정이 잘못된 것이다.
      soldOut.add(1);
    } else {
      unexpectedResponse.add(1);
    }

    systemErrorRate.add(false);
    return;
  }

  systemError.add(1);
  systemErrorRate.add(true);
}

function validateEnvironment() {
  if (!couponId) {
    fail('COUPON_ID environment variable is required.');
  }
  if (!STRATEGIES.includes(strategy)) {
    fail(`STRATEGY must be one of ${STRATEGIES.join(', ')}. Given: ${strategy}`);
  }
  if (!SCENARIOS.includes(scenario)) {
    fail(`SCENARIO must be one of ${SCENARIOS.join(', ')}. Given: ${scenario}`);
  }
  if (!Number.isInteger(vus) || vus <= 0) {
    fail('VUS must be a positive integer.');
  }
  if (!Number.isInteger(iterations) || iterations <= 0) {
    fail('ITERATIONS must be a positive integer.');
  }
  if (!Number.isInteger(targetUserId) || targetUserId <= 0) {
    fail('TARGET_USER_ID must be a positive integer.');
  }
  if (strategy === 'KAFKA') {
    if (!Number.isFinite(kafkaWaitTimeout) || kafkaWaitTimeout <= 0) {
      fail('KAFKA_WAIT_TIMEOUT must be a positive number.');
    }
    if (!Number.isFinite(kafkaPollInterval) || kafkaPollInterval <= 0) {
      fail('KAFKA_POLL_INTERVAL must be a positive number.');
    }
  }
}

function parseJson(response) {
  if (!response || !response.body) return null;

  try {
    return JSON.parse(response.body);
  } catch {
    return null;
  }
}

// 오류 응답의 코드 위치가 확정되지 않아 common.js와 동일하게 여러 후보를 확인한다.
function findErrorCode(body) {
  if (!body) {
    return null;
  }

  return (
    body.code ||
    body.errorCode ||
    (body.error && body.error.code) ||
    (body.data && body.data.code) ||
    null
  );
}
