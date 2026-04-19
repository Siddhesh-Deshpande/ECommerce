import http from 'k6/http';

export let options = {
  scenarios: {
    constant_request_rate: {
      executor: 'constant-arrival-rate',
      rate: __ENV.VUS ? parseInt(__ENV.VUS) : 1000,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 1000,
      maxVUs: 4000,
    },
  },
};

export default function () {
  const url = 'http://192.168.49.2:32603/ecomm/order';

  const payload = JSON.stringify({
    "clientid":2,
    "items":[
        {
            "id":1,
            "quantity":1,
            "price":10
        }
    ]
});

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  http.post(url, payload, params);
}