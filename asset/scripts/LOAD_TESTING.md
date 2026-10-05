# Reproducible load and scaling measurements

## One-command experiment

From the repository root, run `./run.sh`. It applies PostgreSQL, single-node Apache Kafka in combined KRaft mode, monitoring, and application manifests in dependency order; preserves the existing `ecomm` namespace and PostgreSQL data; idempotently ensures `coordinator_db` exists; restarts the app deployments so they pull the images tagged `latest`; resets the test baseline to one replica and applies KEDA ScaledObjects; starts the Kubernetes observer; runs k6; and writes raw data plus a report under `results/<UTC timestamp>/`.

The Kafka service name and port stay `kafka-service:9092`, so application, Kafka exporter, and KEDA bootstrap settings remain compatible. When migrating an existing ZooKeeper-based cluster, the script replaces the Kafka StatefulSet, removes ZooKeeper workloads/services, retains the old Kafka and ZooKeeper PVCs, and creates a fresh KRaft data PVC. Existing Kafka topic data is not migrated. This single combined broker/controller is intended for this local experiment, not high availability.

The script requires a reachable Kubernetes context with a default StorageClass (or working persistent volumes), KEDA already installed, Docker, Python 3, curl, and k6. It asks you to confirm the selected context. Use `./run.sh --deploy-only` to apply and check deployments without generating load, or `./run.sh --skip-deploy` to test an already deployed stack. For a remote cluster, set `METRICS_COLLECTOR_IMAGE` to a registry image name; the script builds and pushes the observer image. For Minikube and Kind it loads the image into the local cluster.

The Prometheus configuration discovers each Spring service pod directly. This lets the Coordinator KEDA query sum the per-pod request counters when the Coordinator scales out.

The k6 script records four distinct phases: a 3 RPS baseline, a sustained 15 RPS burst, a sustained 50 RPS burst, then a 3 RPS recovery phase. Coordinator scales on filtered order-submission RPS and response-topic lag. The participant services scale on their Kafka consumer-group lag. The Coordinator request-rate target is currently 8 requests/second per replica, pending calibration from balanced-load measurements. Adjust `BURST_RATE` and `PEAK_RATE` for other profiles.

For the current Minikube node budget (8 GiB / 6 vCPU), each KEDA ScaledObject is capped at three replicas. The Kafka topics have five partitions, but the replica cap is intentionally lower until the full stack's resource and PostgreSQL limits have been measured. Raise the caps to five only after the node and database sustain the load without pending pods, connection saturation, or lock contention.

Each submitted order rotates deterministically across the seeded users and inventory items using k6's per-scenario iteration number, independent of VU allocation. Defaults match the PostgreSQL seed: users 1–12 and items 1–12. If the seed IDs or prices change, pass the available IDs and matching prices, for example:

```bash
k6 run \
  -e LOAD_TEST_USER_IDS=1,2,3,4 \
  -e LOAD_TEST_ITEM_IDS=10,11,12 \
  -e LOAD_TEST_ITEM_PRICES=10:20,11:25,12:30 \
  asset/scripts/load_testing.js
```

Every item is ordered with quantity 1. The configured item price is sent in the request so the Order and Payment services charge the same amount as the Inventory catalog.

The test measures Coordinator `POST /ecomm/order` latency separately from Order Service status-poll latency. Each submitted order is followed by correlation ID until `ORDER_COMPLETED` or the configured timeout. This repeated status polling creates real Order Service and PostgreSQL read load. The current Order ScaledObject deliberately does not use GET request rate as a scaling signal; it scales on Kafka work only. A single end-of-run status query would show completion by the query time, not actual completion latency, so it should not replace polling unless completion timing is collected passively from durable workflow timestamps or a completion metric.

## 1. Deploy probe and application changes

Rebuild and deploy the updated Java services and apply the Deployment manifests. The manifests use a startup probe, a liveness probe, and a readiness probe. Readiness includes Spring readiness state; the database-backed services also require a healthy database. Liveness only checks the application process, so a dependency outage does not cause restart loops. Kafka connectivity is not added to readiness in this implementation; Kafka failures show up in end-to-end completion and lag data instead.

The collector uses Pod creation and Ready-condition timestamps from Kubernetes. Each service also publishes `application.ready.timestamp` at Spring's `ApplicationReadyEvent`, allowing a separate interval from container start to Spring application ready.

## 2. Start the Kubernetes observer before the load

The observer watches Pods, Deployments, KEDA ScaledObjects, and the HPAs managed by KEDA. It also reads the per-pod Spring ready timestamp. Run it inside the cluster so the pod IPs are reachable:

```bash
docker build -f asset/scripts/metrics-collector.Dockerfile -t load-metrics-collector:latest .
```

For Minikube, load the local image into the cluster:

```bash
minikube image load load-metrics-collector:latest
```

For another cluster, push the image to a registry reachable by the nodes and set that image in `metrics-collector-deployment.yaml`. Apply the read-only permissions and start the observer:

```bash
kubectl apply -f asset/scripts/metrics-collector-rbac.yaml
kubectl apply -f asset/scripts/metrics-collector-deployment.yaml
kubectl -n ecomm rollout status deployment/load-metrics-collector
```

Start a log capture in a separate terminal before k6. Create the results directory first:

```bash
mkdir -p results
kubectl -n ecomm logs -f deployment/load-metrics-collector > results/kubernetes-events.jsonl
```

The collector has read-only access to lifecycle/scaling resources in namespace `ecomm`. Its only application request is a GET to each pod's Actuator ready-timestamp metric.

## 3. Run k6

For local Java services:

```bash
k6 run \
  --out json=results/k6.jsonl \
  --summary-export=results/k6-summary.json \
  asset/scripts/load_testing.js
```

The local mode measures request and order-completion latency only; it cannot measure Kubernetes scale-up or pod startup. Use the Kubernetes mode for those metrics.

For Kubernetes, the Coordinator is exposed through NodePort and Order Service is ClusterIP in the current manifests. Port-forward Order Service in another terminal and provide the Coordinator NodePort URL:

```bash
kubectl -n ecomm port-forward service/order-service 8083:80
k6 run \
  --out json=results/k6.jsonl \
  --summary-export=results/k6-summary.json \
  -e COORDINATOR_URL=http://<node-ip>:<coordinator-node-port> \
  -e ORDER_SERVICE_URL=http://127.0.0.1:8083 \
  -e BURST_RATE=15 \
  -e PEAK_RATE=50 \
  asset/scripts/load_testing.js
```

Leave the observer running through the recovery phase. Stop the log capture with Ctrl-C, then stop the collector while preserving `results/kubernetes-events.jsonl` and the k6 output files:

```bash
kubectl -n ecomm scale deployment/load-metrics-collector --replicas=0
```

## 4. Calculate the report

Install the observer's Python dependency only if running its script directly; the analyzer itself uses the Python standard library.

```bash
python3 asset/scripts/analyze_load_test.py \
  --k6 results/k6.jsonl \
  --kubernetes results/kubernetes-events.jsonl \
  --output results/metrics-report.json \
  --slo-p95-ms 500 \
  --slo-window-seconds 30 \
  --slo-min-requests 30
```

The report includes per-phase request latency (mean, p50, p95, p99), request throughput, HTTP/submission/completion failure rates, end-to-end order completion latency, dropped k6 iterations, pod lifecycle percentiles, and scale-up timing per KEDA-managed HPA. SLO violations are calculated on fixed 30-second windows with at least 30 submission samples; for the default strict `<500 ms` SLO, recovery requires two consecutive eligible windows below 500 ms. Windows with too few requests are omitted rather than treated as passing.

The startup report separates:

- Pod creation to Kubernetes Ready condition.
- Container start to Spring `ApplicationReadyEvent`.
- Spring ready event to Pod Ready condition (probe/dependency and scheduling interval).

The scale report defines `T0` as the first load marker emitted immediately before a phase's first POST. `T1` is the first observed HPA external metric value above its configured target when that metric was below target at phase start. `T2` is the collector-observed desired-replica increase, `T3` is the new pod's API creation timestamp, and `T4` is its Ready-condition transition timestamp. This gives `T1−T0` for metric reaction, `T2−T0` for replica decision, `T2−T1` for decision delay, `T4−T2` for pod provisioning, and `T4−T0` for total response. T1 is left unset when the HPA metric sample is unavailable or already above target at phase start. The collector watch receipt time is used for T1/T2, so their resolution is affected by API watch delivery and cluster clock synchronization. Use synchronized clocks on the k6 runner, cluster nodes, and API control plane.

## Interpretation and limits

Check `dropped_iterations` before interpreting throughput: dropped k6 iterations mean the load generator did not sustain the configured arrival rate. The test sends requests through the Coordinator. Coordinator scales on submission rate or response-topic lag; Order, Inventory, and Payment scale on their Kafka lag. KEDA may correctly leave a worker at one replica if it keeps up. The Coordinator and each participant are capped at three replicas for the current Minikube budget.

The coordinator stores workflow state durably in PostgreSQL and publishes Kafka commands through a transactional outbox. Any Coordinator replica can process a response. Compare completion rate, consumer lag, and database contention when interpreting scale-out; a larger replica count alone does not prove that the system processed more orders.

Use repeated runs and report the exact image digest, resource requests/limits, replica bounds, node type/count, Kubernetes/KEDA versions, and test profile. A single run is not enough to make a strong performance claim. The observer emits raw timestamped JSONL so the percentile and event calculations can be independently reviewed.

The JSON report includes observed image IDs, deployment resource settings, and HPA bounds. Capture the remaining cluster context with the run artifacts:

```bash
kubectl version -o yaml > results/kubernetes-version.yaml
kubectl get nodes -o wide > results/nodes.txt
kubectl -n ecomm get scaledobjects,hpa,deployments -o yaml > results/scaling-configuration.yaml
```
