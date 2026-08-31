#!/usr/bin/env node
/**
 * k6 결과 JSON을 읽어 experiment-results.md에 붙일 마크다운 표를 출력한다.
 *
 * 사용법:
 *   node load-test/summarize-results.js [결과디렉터리] [--env aws]
 *
 * 파일명 규칙: {전략}-{환경}-{단계}-{회차}.json  (예: direct-aws-basic-2.json)
 * 같은 이름의 .log 파일이 있으면 k6가 출력한 final status에서
 * issueCount와 잔여재고를 함께 읽는다.
 *
 *   k6 run ... direct.js 2>&1 | tee load-test/results/direct-aws-basic-1.log
 */

const fs = require('fs');
const path = require('path');

const STRATEGY_ORDER = [
  'direct',
  'pessimistic',
  'optimistic',
  'conditional',
  'redis',
  'kafka',
];

// 재고 저장소가 Redis인 전략은 잔여재고 판정 기준이 다르다. (설계 문서 §9.2)
const REDIS_STOCK_STRATEGIES = new Set(['redis', 'kafka']);

const STAGE_ORDER = ['smoke', 'basic', 'mid', 'max', 'final'];
const STAGE_LABEL = {
  smoke: '스모크',
  basic: '기본',
  mid: '중간',
  max: '공통최대',
  final: '최종',
};

function main() {
  const args = process.argv.slice(2);
  const envFilterIndex = args.indexOf('--env');
  const envFilter = envFilterIndex >= 0 ? args[envFilterIndex + 1] : null;
  const dir =
    args.find((a) => !a.startsWith('--') && a !== envFilter) ||
    'load-test/results';

  if (!fs.existsSync(dir)) {
    console.error(`결과 디렉터리를 찾을 수 없습니다: ${dir}`);
    process.exit(1);
  }

  const runs = fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .map((f) => parseRun(dir, f))
    .filter((r) => r !== null)
    .filter((r) => !envFilter || r.env === envFilter);

  if (runs.length === 0) {
    console.error(`읽을 수 있는 결과 파일이 없습니다: ${dir}`);
    process.exit(1);
  }

  const stages = groupBy(runs, (r) => r.stage);

  for (const stage of sortKeys(Object.keys(stages), STAGE_ORDER)) {
    console.log(`\n### ${STAGE_LABEL[stage] || stage}\n`);
    printStageTable(stages[stage]);
  }

  console.log('\n');
  printWarnings(runs);
}

function parseRun(dir, filename) {
  const base = filename.replace(/\.json$/, '');
  const dash = base.indexOf('-');
  if (dash < 0) return null;

  const strategy = base.slice(0, dash);
  const runId = base.slice(dash + 1);
  // runId = {환경}-{단계}-{회차}
  const parts = runId.split('-');
  if (parts.length < 3) return null;

  const run = parts[parts.length - 1];
  const stage = parts[parts.length - 2];
  const env = parts.slice(0, -2).join('-');

  let data;
  try {
    data = JSON.parse(fs.readFileSync(path.join(dir, filename), 'utf8'));
  } catch (e) {
    console.error(`파싱 실패, 건너뜁니다: ${filename} (${e.message})`);
    return null;
  }

  return {
    strategy,
    env,
    stage,
    run,
    filename,
    ...extractMetrics(data, strategy),
    ...readFinalStatus(dir, base, strategy),
  };
}

function extractMetrics(data, strategy) {
  const m = data.metrics || {};
  const p = strategy.toLowerCase();

  const counter = (name) => {
    const metric = m[name];
    return metric && metric.values ? metric.values.count || 0 : 0;
  };

  const issueDuration = m['http_req_duration{name:coupon_issue}'];
  const dv = (issueDuration && issueDuration.values) || {};

  const thresholdOk = (name, expr) => {
    const metric = m[name];
    if (!metric || !metric.thresholds || !metric.thresholds[expr]) return null;
    return metric.thresholds[expr].ok === true;
  };

  // 응답시간 threshold는 표현식이 바뀔 수 있으므로 이름 대신 첫 항목을 본다.
  const firstThresholdOk = (name) => {
    const metric = m[name];
    if (!metric || !metric.thresholds) return null;
    const keys = Object.keys(metric.thresholds);
    if (keys.length === 0) return null;
    return {
      expr: keys[0],
      ok: metric.thresholds[keys[0]].ok === true,
    };
  };

  return {
    success: counter(`${p}_issue_success`),
    soldOut: counter(`${p}_issue_sold_out`),
    duplicate:
      counter(`${p}_issue_duplicate_request`) +
      counter(`${p}_issue_duplicate_user`),
    systemError: counter(`${p}_issue_system_error`),
    unexpected: counter(`${p}_issue_unexpected_response`),
    iterations: counter('iterations'),
    // 발급 요청 처리량. iterations 한 번이 발급 요청 한 건이다.
    reqPerSec: m.iterations && m.iterations.values ? m.iterations.values.rate : null,
    avg: dv.avg ?? null,
    p95: dv['p(95)'] ?? null,
    p99: dv['p(99)'] ?? null,
    errorOk: thresholdOk(`${p}_system_error_rate`, 'rate==0'),
    consistencyOk: thresholdOk(`${p}_consistency_check_rate`, 'rate==1'),
    durationThreshold: firstThresholdOk('http_req_duration{name:coupon_issue}'),
    failedThreshold: firstThresholdOk('http_req_failed{name:coupon_issue}'),
    couponId: data.setup_data ? data.setup_data.couponId : null,
  };
}

// k6 stdout 로그에 남는 `final status: {...}` 줄에서 최종 상태를 읽는다.
// k6가 console.log를 msg="..." 안에 넣으면서 따옴표를 이스케이프하므로 되돌려서 파싱한다.
function readFinalStatus(dir, base, strategy) {
  const empty = {
    issueCount: null,
    remaining: null,
    consistent: null,
    logStatus: 'missing',
  };

  const logPath = path.join(dir, `${base}.log`);
  if (!fs.existsSync(logPath)) return empty;

  const log = fs.readFileSync(logPath, 'utf8');
  const match = log.match(/final status:\s*(\{.*?\})/s);
  if (!match) return { ...empty, logStatus: 'no-status-line' };

  const status = parseStatus(match[1]);
  if (status === null) return { ...empty, logStatus: 'parse-failed' };

  return {
    issueCount: status.issueCount ?? null,
    remaining: REDIS_STOCK_STRATEGIES.has(strategy.toLowerCase())
      ? status.redisRemainingQuantity ?? null
      : status.dbRemainingQuantity ?? null,
    consistent: status.consistent ?? null,
    logStatus: 'ok',
  };
}

function parseStatus(raw) {
  for (const candidate of [raw, raw.replace(/\\"/g, '"')]) {
    try {
      return JSON.parse(candidate);
    } catch {
      // 다음 후보로 넘어간다
    }
  }
  return null;
}

function printStageTable(runs) {
  console.log(
    '| 전략 | 회차 | SUCCESS | SOLD_OUT | 중복 | 오류 | req/s | avg | p95 | p99 | issueCount | 잔여재고 | 정합성 |',
  );
  console.log(
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|',
  );

  const byStrategy = groupBy(runs, (r) => r.strategy);

  for (const strategy of sortKeys(Object.keys(byStrategy), STRATEGY_ORDER)) {
    const list = byStrategy[strategy].sort((a, b) => a.run.localeCompare(b.run));
    const label = strategy.toUpperCase();

    for (const r of list) {
      console.log(row(label, r.run, r, false));
    }
    if (list.length > 1) {
      console.log(row(`**${label}**`, '**평균**', average(list), true));
    }
  }
}

function row(label, run, r, isAverage) {
  // DIRECT는 정합성 통과를 강제하지 않는 기준 전략이다. (설계 문서 §9.2)
  let verdict;
  if (String(label).replace(/\*/g, '').toUpperCase() === 'DIRECT') {
    verdict = '관찰';
  } else if (isAverage || r.consistencyOk === null) {
    verdict = '-';
  } else {
    verdict = r.consistencyOk ? 'O' : 'X';
  }

  return [
    label,
    run,
    num(r.success, 0),
    num(r.soldOut, 0),
    num(r.duplicate, 0),
    num(r.systemError, 0),
    num(r.reqPerSec, 2),
    num(r.avg, 2),
    num(r.p95, 2),
    num(r.p99, 2),
    num(r.issueCount, 0),
    num(r.remaining, 0),
    verdict,
  ].join(' | ')
    .replace(/^/, '| ')
    .replace(/$/, ' |');
}

function average(list) {
  const mean = (key) => {
    const vals = list.map((r) => r[key]).filter((v) => typeof v === 'number');
    if (vals.length === 0) return null;
    return vals.reduce((a, b) => a + b, 0) / vals.length;
  };

  return {
    success: mean('success'),
    soldOut: mean('soldOut'),
    duplicate: mean('duplicate'),
    systemError: mean('systemError'),
    reqPerSec: mean('reqPerSec'),
    avg: mean('avg'),
    p95: mean('p95'),
    p99: mean('p99'),
    issueCount: mean('issueCount'),
    remaining: mean('remaining'),
    consistencyOk: null,
  };
}

function printWarnings(runs) {
  const lines = [];

  for (const r of runs) {
    if (r.errorOk === false) {
      lines.push(
        `- ${r.filename}: 시스템 오류 threshold 실패 (오류 ${r.systemError}건)`,
      );
    }
    if (r.consistencyOk === false && r.strategy !== 'direct') {
      lines.push(`- ${r.filename}: 정합성 threshold 실패 — 원인 확인 필요`);
    }
    if (r.unexpected > 0) {
      lines.push(`- ${r.filename}: 예상하지 못한 응답 ${r.unexpected}건`);
    }
    if (r.durationThreshold && !r.durationThreshold.ok) {
      lines.push(
        `- ${r.filename}: 응답시간 threshold 초과 (${r.durationThreshold.expr}, p95 ${num(r.p95, 2)}ms)`,
      );
    }
    if (r.failedThreshold && !r.failedThreshold.ok) {
      lines.push(
        `- ${r.filename}: HTTP 실패율 threshold 초과 (${r.failedThreshold.expr})`,
      );
    }
    if (r.logStatus === 'missing') {
      lines.push(
        `- ${r.filename}: 로그 파일이 없습니다. 실행 시 \`| tee ...log\`를 빠뜨렸는지 확인하세요`,
      );
    } else if (r.logStatus !== 'ok') {
      lines.push(
        `- ${r.filename}: 로그에서 final status를 읽지 못했습니다 (${r.logStatus})`,
      );
    }
  }

  if (lines.length === 0) {
    console.log('확인이 필요한 항목 없음');
    return;
  }

  console.log('## 확인이 필요한 항목\n');
  console.log(lines.join('\n'));
}

function num(v, digits) {
  if (v === null || v === undefined || Number.isNaN(v)) return '-';
  if (typeof v !== 'number') return String(v);
  return digits === 0 ? String(Math.round(v)) : v.toFixed(digits);
}

function groupBy(list, keyFn) {
  return list.reduce((acc, item) => {
    const key = keyFn(item);
    (acc[key] = acc[key] || []).push(item);
    return acc;
  }, {});
}

function sortKeys(keys, order) {
  return keys.sort((a, b) => {
    const ia = order.indexOf(a);
    const ib = order.indexOf(b);
    if (ia === -1 && ib === -1) return a.localeCompare(b);
    if (ia === -1) return 1;
    if (ib === -1) return -1;
    return ia - ib;
  });
}

main();
