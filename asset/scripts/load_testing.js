import http from 'k6/http';

export let options = {
  scenarios: {
    constant_request_rate: {
      executor: 'constant-arrival-rate',
      rate: __ENV.VUS ? parseInt(__ENV.VUS) : 200,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 1000,
      maxVUs: 4000,
    },
  },
};

export default function () {
  const url = 'http://192.168.49.2:30270/ecomm/order';

  const payload = JSON.stringify({
    "clientid":3,
    "items":[
        {
            "id":3,
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