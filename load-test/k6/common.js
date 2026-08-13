import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

export function createCouponIssueTest(strategy) {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const couponId = __ENV.COUPON_ID;
  const vus = Number(__ENV.VUS || 20);
  const iterations = Number(__ENV.ITERATIONS || 20);
  const userIdStart = Number(__ENV.USER_ID_START || 1);
  // k6의 init 코드는 VU마다 실행되므로 Date.now()를 기본값으로 사용하지 않는다.
  const runId = __ENV.RUN_ID || 'local-run';
  const resultFile =
    __ENV.RESULT_FILE ||
    `load-test/results/${strategy.toLowerCase()}-${runId}.json`;

  const issueSuccess = new Counter(`${strategy.toLowerCase()}_issue_success`);
  const soldOut = new Counter(`${strategy.toLowerCase()}_issue_sold_out`);
  const duplicateRequest = new Counter(
    `${strategy.toLowerCase()}_issue_duplicate_request`,
  );
  const duplicateUser = new Counter(
    `${strategy.toLowerCase()}_issue_duplicate_user`,
  );
  const systemError = new Counter(`${strategy.toLowerCase()}_issue_system_error`);
  const unexpectedResponse = new Counter(
    `${strategy.toLowerCase()}_issue_unexpected_response`,
  );
  const systemErrorRate = new Rate(
    `${strategy.toLowerCase()}_system_error_rate`,
  );
  const consistencyCheckRate = new Rate(
    `${strategy.toLowerCase()}_consistency_check_rate`,
  );

  // 품절과 중복을 나타내는 409는 부하 테스트에서 예상되는 비즈니스 응답이다.
  http.setResponseCallback(http.expectedStatuses(200, 409));

  const options = {
    scenarios: {
      couponIssue: {
        executor: 'shared-iterations',
        vus,
        iterations,
        maxDuration: __ENV.MAX_DURATION || '2m',
      },
    },
    thresholds: {
      [`${strategy.toLowerCase()}_system_error_rate`]: ['rate==0'],
      [`${strategy.toLowerCase()}_consistency_check_rate`]: ['rate==1'],
      // 초기화와 상태 조회를 제외하고 발급 요청만 성능 지표로 평가한다.
      'http_req_duration{name:coupon_issue}': ['p(95)<3000'],
      'http_req_failed{name:coupon_issue}': ['rate<0.01'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  };

  function setup() {
    validateEnvironment();

    const response = http.post(
      `${baseUrl}/experiment/coupons/${couponId}/reset`,
      null,
      {
        tags: { name: 'coupon_reset', strategy },
      },
    );

    const succeeded = check(response, {
      'coupon reset succeeds': (res) => res.status === 200,
    });

    if (!succeeded) {
      fail(
        `Coupon reset failed: status=${response.status}, body=${response.body}`,
      );
    }

    return { couponId };
  }

  function run(data) {
    const sequence = exec.scenario.iterationInTest;
    const userId = userIdStart + sequence;
    const requestId = buildRequestId(sequence);

    const response = http.post(
      `${baseUrl}/experiment/coupons/${data.couponId}/issue?strategy=${strategy}`,
      JSON.stringify({ userId, requestId }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'coupon_issue', strategy },
      },
    );

    classifyResponse(response);
  }

  function teardown(data) {
    const response = http.get(
      `${baseUrl}/experiment/coupons/${data.couponId}/status`,
      {
        tags: { name: 'coupon_status', strategy },
      },
    );

    const statusValid = check(response, {
      'coupon status lookup succeeds': (res) => res.status === 200,
      'stock and issue history are consistent': (res) => {
        const body = parseJson(res);
        return body !== null && body.consistent === true;
      },
      'remaining stock is not negative': (res) => {
        const body = parseJson(res);
        return body !== null && body.dbRemainingQuantity >= 0;
      },
    });

    // 정합성 검증 실패가 단순 로그가 아니라 threshold 실패로 반영되도록 기록한다.
    consistencyCheckRate.add(statusValid);

    console.log(`[${strategy}] final status: ${response.body}`);
  }

  function handleSummary(data) {
    return {
      [resultFile]: JSON.stringify(data, null, 2),
      stdout:
        `\n${strategy} load test completed.\n` +
        `Result file: ${resultFile}\n`,
    };
  }

  function validateEnvironment() {
    if (!couponId) {
      fail('COUPON_ID environment variable is required.');
    }
    if (!Number.isInteger(vus) || vus <= 0) {
      fail('VUS must be a positive integer.');
    }
    if (!Number.isInteger(iterations) || iterations <= 0) {
      fail('ITERATIONS must be a positive integer.');
    }
    if (!Number.isInteger(userIdStart) || userIdStart <= 0) {
      fail('USER_ID_START must be a positive integer.');
    }
  }

  function buildRequestId(sequence) {
    const prefix = strategy.toLowerCase();
    const requestId = `${prefix}-${runId}-${sequence}`;

    if (requestId.length > 64) {
      fail('Generated requestId exceeds the maximum length of 64 characters.');
    }

    return requestId;
  }

  function classifyResponse(response) {
    if (response.status === 200) {
      const body = parseJson(response);
      if (body !== null && body.result === 'SUCCESS') {
        issueSuccess.add(1);
        systemErrorRate.add(false);
      } else {
        unexpectedResponse.add(1);
        systemErrorRate.add(true);
      }
      return;
    }

    const errorCode = findErrorCode(parseJson(response));

    if (response.status === 409 && errorCode === 'EXPERIMENT409-0') {
      soldOut.add(1);
      systemErrorRate.add(false);
      return;
    }
    if (response.status === 409 && errorCode === 'EXPERIMENT409-1') {
      duplicateRequest.add(1);
      systemErrorRate.add(false);
      return;
    }
    if (response.status === 409 && errorCode === 'EXPERIMENT409-2') {
      duplicateUser.add(1);
      systemErrorRate.add(false);
      return;
    }
    if (response.status >= 500 || response.status === 0) {
      systemError.add(1);
      systemErrorRate.add(true);
      return;
    }

    unexpectedResponse.add(1);
    systemErrorRate.add(true);
  }

  function parseJson(response) {
    try {
      return response.json();
    } catch (error) {
      return null;
    }
  }

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

  return {
    options,
    setup,
    run,
    teardown,
    handleSummary,
  };
}
