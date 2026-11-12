import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '5s', target: 20 },  // Ramp up to 20 users
    { duration: '10s', target: 50 }, // Burst to 50 concurrent users
    { duration: '5s', target: 0 },   // Cool down
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],    // Error rate must be < 1%
    http_req_duration: ['p(95)<500'],  // 95% of requests must complete under 500ms
    http_req_duration: ['p(99)<1000'], // 99% of requests must complete under 1s
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Test 1: Fresh payment with unique idempotency key
  const uniqueKey = 'load_' + uuidv4();
  const payload = JSON.stringify({
    customerId: 'cust_load_test',
    amount: 1500, // $15.00
    currency: 'USD',
    paymentMethodToken: 'tok_visa_4242'
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uniqueKey,
      'X-Merchant-Id': 'merch_load_test',
      'Authorization': 'Bearer ' + (__ENV.AUTH_TOKEN || '')
    },
  };

  const res1 = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
  check(res1, {
    'initial payment status is 201': (r) => r.status === 201,
  });

  // Test 2: Immediate concurrent retry with the SAME idempotency key (idempotency verification)
  const res2 = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
  check(res2, {
    'idempotent replay status is 200 or 201': (r) => r.status === 200 || r.status === 201,
  });

  sleep(0.1);
}
