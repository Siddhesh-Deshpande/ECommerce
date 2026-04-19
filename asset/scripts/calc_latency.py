import json
import requests
import math

# User can change this Jaeger API base URL
JAEGER_API_BASE = "http://192.168.49.2:31678/api/traces"
SERVICES = ["coordinator", "inventory-service", "payment-service", "order-service"]
LIMIT = 20000

def fetch_traces(service):
    url = f"{JAEGER_API_BASE}?service={service}&limit={LIMIT}"
    try:
        print(f"Fetching traces for {service} ...")
        response = requests.get(url)
        response.raise_for_status()
        return response.json().get('data', [])
    except Exception as e:
        print(f"Error fetching traces for {service}: {e}")
        return []

correlations = {}
processed_spans = set()

for service in SERVICES:
    traces_data = fetch_traces(service)
    for trace in traces_data:
        trace_id = trace.get('traceID')
        if not trace_id: continue
        
        for span in trace.get('spans', []):
            span_id = span.get('spanID')
            # Avoid processing the same span multiple times if traces overlap across services
            if span_id in processed_spans:
                continue
            processed_spans.add(span_id)

            tags = span.get('tags', [])
            # Only process spans that actually belong to a transaction (have a correlationId)
            correlation_id_tag = next((t['value'] for t in tags if t['key'] == 'correlationId'), None)
            
            if not correlation_id_tag:
                continue
                
            cid = correlation_id_tag
                
            if cid not in correlations:
                correlations[cid] = {'starts': [], 'ends': []}
            
            start_time = span.get('startTime')
            duration = span.get('duration')
            
            if start_time is not None and duration is not None:
                correlations[cid]['starts'].append(start_time)
                correlations[cid]['ends'].append(start_time + duration)

latencies = []
for cid, times in correlations.items():
    if not times['starts']: continue
    min_start = min(times['starts'])
    max_end = max(times['ends'])
    latency_us = max_end - min_start
    latencies.append(latency_us)

if latencies:
    latencies_ms = sorted([l / 1000.0 for l in latencies])
    avg_latency = sum(latencies_ms) / len(latencies_ms)
    
    def get_percentile(data, p):
        index = (len(data) - 1) * p / 100.0
        lower = int(index)
        upper = lower + 1
        weight = index - lower
        if upper >= len(data):
            return data[-1]
        return data[lower] * (1.0 - weight) + data[upper] * weight

    p50 = get_percentile(latencies_ms, 50)
    p95 = get_percentile(latencies_ms, 95)
    p99 = get_percentile(latencies_ms, 99)

    print(f"Processed {len(latencies_ms)} correlation IDs/traces.")
    print(f"Average Latency: {avg_latency:.2f} ms")
    print(f"50th Percentile (Median): {p50:.2f} ms")
    print(f"95th Percentile: {p95:.2f} ms")
    print(f"99th Percentile: {p99:.2f} ms")
else:
    print("No traces found.")
