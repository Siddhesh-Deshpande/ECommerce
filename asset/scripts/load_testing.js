import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const coordinatorUrl = (__ENV.COORDINATOR_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const burstRate = Number(__ENV.BURST_RATE || 15);
const peakRate = Number(__ENV.PEAK_RATE || 50);
const requestTimeoutSeconds = Number(__ENV.HTTP_TIMEOUT_SECONDS || 5);
const vuSafetyFactor = Number(__ENV.VU_SAFETY_FACTOR || 1.2);
const highestRate = Math.max(burstRate, peakRate);
const requiredVUs = Math.max(50, Math.ceil(highestRate * requestTimeoutSeconds * vuSafetyFactor));
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || requiredVUs);
const userIds = parseIdList('LOAD_TEST_USER_IDS', range(1, 12));
const itemIds = parseIdList('LOAD_TEST_ITEM_IDS', range(1, 12));
const itemStride = coprimeStride(itemIds.length);
const itemPrices = parseItemPrices(__ENV.LOAD_TEST_ITEM_PRICES || '1:10,2:10,3:10,4:10,5:10,6:10,7:20,8:20,9:20,10:20,11:20,12:20');

if (!Number.isInteger(burstRate) || burstRate <= 0 || !Number.isInteger(peakRate) || peakRate <= 0) {
  throw new Error('BURST_RATE and PEAK_RATE must be positive integers');
}
if (!Number.isFinite(requestTimeoutSeconds) || requestTimeoutSeconds <= 0
    || !Number.isFinite(vuSafetyFactor) || vuSafetyFactor < 1) {
  throw new Error('HTTP_TIMEOUT_SECONDS must be positive and VU_SAFETY_FACTOR must be at least 1');
}
if (!Number.isInteger(preAllocatedVUs) || preAllocatedVUs < requiredVUs) {
  throw new Error(
    `PREALLOCATED_VUS must be an integer >= ${requiredVUs} for maximum phase rate ${highestRate} RPS, `
    + `${requestTimeoutSeconds}s request timeout, and ${vuSafetyFactor}x safety factor`,
  );
}

for (const itemId of itemIds) {
  if (itemPrices[itemId] === undefined) {
    throw new Error(`No unit price configured for load-test item ${itemId}; set LOAD_TEST_ITEM_PRICES=id:price,...`);
  }
}

const phaseMarkers = new Counter('load_phase_marker');
const ordersSubmitted = new Counter('orders_submitted');
const submissionFailed = new Rate('order_submission_failed');

// The measured workload consists only of order submissions. Completion
// timestamps are collected from Coordinator PostgreSQL after the load ends.
http.setResponseCallback(http.expectedStatuses(200));

const transitionDown = [
  { target: 0, duration: '1s' },
  { target: 0, duration: '28s' },
];
const transitionUp = (target) => ({ target, duration: '1s' });

// One ramping-arrival-rate scenario gives all four plateaus a single, shared
// VU pool. The 1s ramps fit inside the existing 30s gaps; requests in those
// gaps receive their own transition phase tags. Plateau start times and
// durations remain (0s, 150s, 480s, 810s) and (2m, 5m, 5m, 2m).
const stages = [
  { target: 3, duration: '2m' },
  ...transitionDown,
  transitionUp(burstRate),
  { target: burstRate, duration: '5m' },
  ...transitionDown,
  transitionUp(peakRate),
  { target: peakRate, duration: '5m' },
  ...transitionDown,
  transitionUp(3),
  { target: 3, duration: '2m' },
];

const profilePhases = [
  { name: 'baseline', start: 0, end: 120 },
  { name: 'transition_baseline_to_burst', start: 120, end: 150 },
  { name: `burst_${burstRate}_rps`, start: 150, end: 450 },
  { name: 'transition_burst_to_peak', start: 450, end: 480 },
  { name: 'peak_burst', start: 480, end: 780 },
  { name: 'transition_peak_to_recovery', start: 780, end: 810 },
  { name: 'recovery', start: 810, end: 930 },
];

export const options = {
  scenarios: {
    load_profile: {
      executor: 'ramping-arrival-rate',
      startRate: 3,
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs: preAllocatedVUs,
      stages,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'http_req_duration{endpoint:order-submission}': ['p(95)<500'],
    'http_req_failed{endpoint:order-submission}': ['rate<0.01'],
    order_submission_failed: ['rate<0.01'],
    // A latency/SLO result is only comparable when k6 actually scheduled the
    // requested arrivals. Any dropped iteration makes the run fail visibly.
    dropped_iterations: ['count==0'],
  },
};

function currentPhase() {
  const elapsedSeconds = (Date.now() - exec.scenario.startTime) / 1000;
  return profilePhases.find((candidate) => elapsedSeconds >= candidate.start && elapsedSeconds < candidate.end);
}

function orderPayload() {
  // iterationInTest advances across the shared profile, so account/item
  // selection does not depend on which VU runs a request.
  const iteration = exec.scenario.iterationInTest;
  const userIndex = iteration % userIds.length;
  const itemCycle = Math.floor(iteration / userIds.length);
  const itemIndex = (itemCycle + userIndex * itemStride) % itemIds.length;
  const itemId = itemIds[itemIndex];

  return JSON.stringify({
    clientid: userIds[userIndex],
    items: [{ id: itemId, quantity: 1, price: itemPrices[itemId] }],
  });
}

function range(first, last) {
  return Array.from({ length: last - first + 1 }, (_, index) => first + index);
}

function parseIdList(name, fallback) {
  const raw = __ENV[name];
  if (!raw) return fallback;
  const values = raw.split(',').map((value) => Number(value.trim()));
  if (values.some((value) => !Number.isInteger(value) || value <= 0)
      || new Set(values).size !== values.length || values.length === 0) {
    throw new Error(`${name} must be a comma-separated list of unique positive integer IDs`);
  }
  return values;
}

function parseItemPrices(raw) {
  const prices = {};
  for (const pair of raw.split(',')) {
    const [idText, priceText] = pair.split(':').map((value) => value.trim());
    const id = Number(idText);
    const price = Number(priceText);
    if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(price) || price < 0) {
      throw new Error('LOAD_TEST_ITEM_PRICES must use id:price pairs, for example 3:10,7:20');
    }
    prices[id] = price;
  }
  return prices;
}

function coprimeStride(length) {
  for (let stride = Math.max(1, Math.floor(length / 2)); stride <= length * 2; stride++) {
    if (greatestCommonDivisor(stride, length) === 1) return stride;
  }
  return 1;
}

function greatestCommonDivisor(left, right) {
  while (right !== 0) {
    [left, right] = [right, left % right];
  }
  return left;
}

function correlationIdFrom(response) {
  try {
    return response.json('correlationId');
  } catch (_) {
    return null;
  }
}

export default function () {
  const phaseWindow = currentPhase();
  const phase = phaseWindow ? phaseWindow.name : undefined;
  if (phaseWindow) {
    const scheduledPhaseStart = new Date(exec.scenario.startTime + phaseWindow.start * 1000).toISOString();
    phaseMarkers.add(1, { phase, phase_start_utc: scheduledPhaseStart });
  }

  const requestTags = phase
    ? { name: 'POST /ecomm/order', endpoint: 'order-submission', phase }
    : { name: 'POST /ecomm/order', endpoint: 'order-submission' };

  const submission = http.post(`${coordinatorUrl}/ecomm/order`, orderPayload(), {
    headers: { 'Content-Type': 'application/json' },
    tags: requestTags,
    timeout: `${requestTimeoutSeconds}s`,
  });

  const correlationId = correlationIdFrom(submission);
  const accepted = check(submission, {
    'order submission returned 200': (response) => response.status === 200,
    'order submission returned correlation ID': () => Boolean(correlationId),
  });
  if (phase) submissionFailed.add(!accepted, { phase });
  if (!accepted) {
    return;
  }

  if (phase) ordersSubmitted.add(1, { phase });
}
