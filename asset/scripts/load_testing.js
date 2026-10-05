import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const coordinatorUrl = (__ENV.COORDINATOR_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const orderServiceUrl = (__ENV.ORDER_SERVICE_URL || 'http://127.0.0.1:8083').replace(/\/$/, '');
const burstRate = Number(__ENV.BURST_RATE || 15);
const peakRate = Number(__ENV.PEAK_RATE || 50);
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || 100);
const maxVUs = Number(__ENV.MAX_VUS || 400);
const pollIntervalSeconds = Number(__ENV.POLL_INTERVAL_SECONDS || 2);
const completionTimeoutSeconds = Number(__ENV.COMPLETION_TIMEOUT_SECONDS || 60);
const userIds = parseIdList('LOAD_TEST_USER_IDS', range(1, 12));
const itemIds = parseIdList('LOAD_TEST_ITEM_IDS', range(1, 12));
const itemStride = coprimeStride(itemIds.length);
const itemPrices = parseItemPrices(__ENV.LOAD_TEST_ITEM_PRICES || '1:10,2:10,3:10,4:10,5:10,6:10,7:20,8:20,9:20,10:20,11:20,12:20');

for (const itemId of itemIds) {
  if (itemPrices[itemId] === undefined) {
    throw new Error(`No unit price configured for load-test item ${itemId}; set LOAD_TEST_ITEM_PRICES=id:price,...`);
  }
}

const phaseMarkers = new Counter('load_phase_marker');
const ordersSubmitted = new Counter('orders_submitted');
const ordersCompleted = new Counter('orders_completed');
const submissionFailed = new Rate('order_submission_failed');
const completionFailed = new Rate('order_completion_failed');
const endToEndLatency = new Trend('order_end_to_end_latency_ms', true);
const orderLookupLatency = new Trend('order_status_lookup_latency_ms', true);

// A 404 is expected only for order-status polling while the order is still
// being created. Submission requests must return 200 to count as successful.
http.setResponseCallback(http.expectedStatuses(200));

function arrivalScenario(rate, duration, startTime) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration,
    startTime,
    preAllocatedVUs,
    maxVUs,
    gracefulStop: '30s',
  };
}

export const options = {
  scenarios: {
    baseline: arrivalScenario(3, '2m', '0s'),
    burst_15_rps: arrivalScenario(burstRate, '5m', '2m30s'),
    peak_burst: arrivalScenario(peakRate, '5m', '8m'),
    recovery: arrivalScenario(3, '2m', '13m30s'),
  },
  thresholds: {
    'http_req_duration{endpoint:order-submission}': ['p(95)<500'],
    'http_req_failed{endpoint:order-submission}': ['rate<0.01'],
    order_submission_failed: ['rate<0.01'],
    order_completion_failed: ['rate<0.01'],
  },
};

function orderPayload() {
  // iterationInTest advances across VUs within this phase, so account/item
  // selection does not depend on how k6 reallocates VUs during a burst.
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
  const phase = exec.scenario.name;
  const startedAt = Date.now();
  phaseMarkers.add(1, { phase });

  const submission = http.post(`${coordinatorUrl}/ecomm/order`, orderPayload(), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /ecomm/order', endpoint: 'order-submission', phase },
  });

  const correlationId = correlationIdFrom(submission);
  const accepted = check(submission, {
    'order submission returned 200': (response) => response.status === 200,
    'order submission returned correlation ID': () => Boolean(correlationId),
  });
  submissionFailed.add(!accepted, { phase });
  if (!accepted) {
    return;
  }

  ordersSubmitted.add(1, { phase });
  const deadline = startedAt + completionTimeoutSeconds * 1000;

  while (Date.now() < deadline) {
    const statusResponse = http.get(
      `${orderServiceUrl}/orders/by-correlation/${encodeURIComponent(correlationId)}`,
      {
        responseCallback: http.expectedStatuses(200, 404),
        tags: { name: 'GET /orders/by-correlation/:correlationId', endpoint: 'order-status', phase },
      },
    );
    orderLookupLatency.add(statusResponse.timings.duration, { phase });

    if (statusResponse.status === 404) {
      sleep(pollIntervalSeconds);
      continue;
    }

    let status;
    try {
      status = statusResponse.json('status');
    } catch (_) {
      status = null;
    }

    if (status === 'ORDER_COMPLETED') {
      ordersCompleted.add(1, { phase });
      completionFailed.add(false, { phase });
      endToEndLatency.add(Date.now() - startedAt, { phase });
      check(statusResponse, { 'order reached ORDER_COMPLETED': (response) => response.status === 200 });
      return;
    }

    if (status === 'ORDER_CANCELLED' || statusResponse.status >= 500) {
      break;
    }

    sleep(pollIntervalSeconds);
  }

  completionFailed.add(true, { phase });
}
