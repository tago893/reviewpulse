// End-to-end HTTP load test for GET /api/search (validation + index + hydration + highlighting).
// Run: k6 run -e BASE_URL=http://127.0.0.1:8080 -e LABEL=cache-on benchmarks/k6/search-load.js
// The backend must run with REVIEWPULSE_RATE_LIMIT_ENABLED=false, or the limiter will (correctly) return 429s.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const RATE = Number(__ENV.RATE || 50);
const LABEL = __ENV.LABEL || 'run';

// Realistic beauty-review vocabulary (all present in the All_Beauty corpus).
const TERMS = ['dry', 'skin', 'hair', 'smell', 'scent', 'greasy', 'oily', 'moisturizer', 'cream', 'lotion', 'shampoo',
  'conditioner', 'soft', 'smooth', 'sensitive', 'acne', 'brush', 'nail', 'polish', 'color', 'lasts', 'price', 'quality',
  'broke', 'cheap', 'love', 'great', 'works', 'curly', 'frizz', 'wig', 'lashes', 'mascara', 'serum', 'face', 'mask'];
const POPULAR = ['dry skin', 'not greasy', 'curly hair', 'smells great', 'nail polish'];
const SORTS = ['relevance', 'recency', 'helpfulness'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function url(params) {
  const qs = Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  return `${BASE}/api/search?${qs}`;
}

export const options = {
  discardResponseBodies: false,
  // No pass/fail gates: these only make k6 compute per-scenario sub-metrics for the summary.
  thresholds: {
    'http_req_duration{scenario:mixed}': ['max>=0'],
    'http_req_duration{scenario:popular}': ['max>=0'],
    'http_req_duration{scenario:cold}': ['max>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    mixed: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '60s', preAllocatedVUs: 20,
      maxVUs: 100, exec: 'mixed', tags: { scenario: 'mixed' } },
    popular: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '30s', preAllocatedVUs: 20,
      maxVUs: 100, exec: 'popular', startTime: '65s', tags: { scenario: 'popular' } },
    cold: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '30s', preAllocatedVUs: 20,
      maxVUs: 100, exec: 'cold', startTime: '100s', tags: { scenario: 'cold' } },
  },
};

function assertOk(res) {
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function mixed() {
  const params = { q: `${pick(TERMS)} ${pick(TERMS)}`, operator: Math.random() < 0.6 ? 'AND' : 'OR', sort: pick(SORTS), pageSize: 20 };
  if (Math.random() < 0.3) params.ratingMin = 4;
  if (Math.random() < 0.2) params.verifiedPurchase = true;
  assertOk(http.get(url(params), { tags: { name: 'search' } }));
}

export function popular() {
  assertOk(http.get(url({ q: pick(POPULAR), pageSize: 20 }), { tags: { name: 'search' } }));
}

export function cold() {
  // Unique per request (random third term), so every lookup is a cache miss.
  const q = `${pick(TERMS)} ${pick(TERMS)} ${pick(TERMS)}${Math.floor(Math.random() * 1e6)}`;
  assertOk(http.get(url({ q, operator: 'OR', pageSize: 20 }), { tags: { name: 'search' } }));
}

export function handleSummary(data) {
  const pick2 = (name) => {
    const m = data.metrics[name];
    return m ? m.values : null;
  };
  const scenarios = {};
  for (const s of ['mixed', 'popular', 'cold']) {
    scenarios[s] = pick2(`http_req_duration{scenario:${s}}`);
  }
  const summary = {
    label: LABEL,
    rateTargetPerScenario: RATE,
    totalRequests: data.metrics.http_reqs.values.count,
    achievedRps: data.metrics.http_reqs.values.rate,
    failedRate: data.metrics.http_req_failed.values.rate,
    durationAll: data.metrics.http_req_duration.values,
    byScenario: scenarios,
  };
  return { [`benchmarks/results/k6-${LABEL}.json`]: JSON.stringify(summary, null, 2), stdout: JSON.stringify(summary, null, 2) };
}
