# Reproducible load and scaling measurements

## Research objective and deferred measurements

The paper's primary story is image strategy → image acquisition/runtime initialization → fresh-Pod readiness → SLO-violation duration after scale-up. Request latency, end-to-end workflow latency, and resource-use results are supporting steady-state parity evidence, not the main contribution. Compare them only across repeated, matched strategy runs at equivalent rates after startup. Do not treat a run as a valid comparison if the load generator fails to deliver scheduled arrivals or phase/workflow measurements are incomplete.

The report collects per-container pod CPU and memory from metrics-server, CPU throttling from Prometheus scraping each kubelet's cAdvisor endpoint, and pod/container/application readiness timestamps. When images were built with `scripts/build-images.sh`, the report also includes build duration, image size, and root filesystem layer count under `image_build`; missing build metadata is reported as missing rather than inferred.

## One-command experiment

From the repository root, run `./run.sh`. It applies PostgreSQL, single-node Apache Kafka in combined KRaft mode, and monitoring; starts the Kubernetes observer before creating/restarting application Pods; applies application manifests and KEDA ScaledObjects; runs k6; and writes raw data plus a report under `results/<UTC timestamp>/`. It preserves the existing `ecomm` namespace and PostgreSQL data and idempotently ensures `coordinator_db` exists.

The Kafka service name and port stay `kafka-service:9092`, so application, Kafka exporter, and KEDA bootstrap settings remain compatible. When migrating an existing ZooKeeper-based cluster, the script replaces the Kafka StatefulSet, removes ZooKeeper workloads/services, retains the old Kafka and ZooKeeper PVCs, and creates a fresh KRaft data PVC. Existing Kafka topic data is not migrated. This single combined broker/controller is intended for this local experiment, not high availability.

The script requires a reachable Kubernetes context with a default StorageClass (or working persistent volumes), KEDA already installed, Docker, Python 3, curl, and k6. It asks you to confirm the selected context. Use `./run.sh --deploy-only` to apply and check deployments without generating load, or `./run.sh --skip-deploy` to test an already deployed stack. For a remote cluster, set `METRICS_COLLECTOR_IMAGE` to a registry image name; the script builds and pushes the observer image. For Minikube and Kind it loads the image into the local cluster.

The Prometheus configuration discovers each Spring service pod directly. This lets the Coordinator KEDA query sum the per-pod request counters when the Coordinator scales out.

The k6 script measures four distinct plateaus: a 3 RPS baseline, a sustained 15 RPS burst, a sustained 50 RPS burst, then a 3 RPS recovery phase. It reports the short transition intervals separately. Each iteration sends one `POST /ecomm/order` and ends; there is no order-status polling in any interval. After submissions finish, `run.sh` waits for workflows to become terminal (up to `WORKFLOW_DRAIN_TIMEOUT_SECONDS`, default 900 seconds) and exports their Coordinator database timestamps. Coordinator scales on filtered order-submission RPS and response-topic lag. The participant services scale on their Kafka consumer-group lag. The Coordinator request-rate target is currently 8 requests/second per replica, pending calibration from balanced-load measurements. Adjust `BURST_RATE` and `PEAK_RATE` for other profiles.

The four plateaus run through one `ramping-arrival-rate` scenario, so they share one VU pool instead of reserving a separate pool per phase. The pool is preallocated before measurement and k6 does not add VUs mid-run. Its default is calculated as `max(50, highest phase RPS × HTTP timeout × 1.2)`: at a highest phase rate of 50 RPS and a 5-second request timeout, that is 300 VUs. This is above the 250 concurrent requests implied by 50 RPS × 5 seconds, with 20% reserve. The HTTP timeout bounds a stuck iteration; requests that exceed it fail and remain visible in latency/error metrics. The one-second rate ramps fit inside the existing 30-second inter-phase gaps; requests in those gaps are tagged and reported as transition phases separately.

For a diagnostic run at one target rate, set `TARGET_RPS`. This selects a single profile: warm-up, gradual ramp-up, target hold, then gradual ramp-down to zero. Phase durations are configurable in seconds. Example for one 15 RPS run:

```bash
TARGET_RPS=15 WARMUP_RATE=3 WARMUP_SECONDS=120 \
RAMP_UP_SECONDS=300 HOLD_SECONDS=300 RAMP_DOWN_SECONDS=120 ./run.sh
```

Use a separate fresh database/Kafka state for each target-rate run. `run.sh` resets application replicas to one, but deliberately preserves PostgreSQL and Kafka persistent data. The observer records 10-second Prometheus samples for total Kafka consumer-group lag, per-partition Kafka lag, the number of lagging partitions, and the total partition count per application topic. The runner samples per-container pod CPU/memory and node CPU/memory through `kubectl top`, records PostgreSQL connections, and collects CPU throttling from Prometheus scraping kubelet cAdvisor metrics. Pod creation, container start, Spring application-ready timestamps, Kubernetes readiness, and HPA desired/current replica changes are captured separately. This single-target profile leaves the existing KEDA settings unchanged.

The run fails k6's `dropped_iterations` threshold if it cannot start every scheduled iteration. A p95 threshold failure with zero dropped iterations means k6 delivered the configured arrivals and the service missed the 500ms latency SLO. A dropped-iteration failure means the requested load was not delivered, so compare the run's `load_generator` and per-phase arrival delivery fields before drawing service conclusions. `load-test-config.txt` records the script SHA-256 and exact VU/rate settings so a VM run can be checked against the script version.

The Coordinator and Order ScaledObjects can each reach five replicas; Inventory and Payment can each reach three. Each app pod caps its Hikari pool at five connections, limiting the application maximum to 80 connections across those 16 replicas and leaving PostgreSQL headroom for operations and reporting. Watch for pending pods, PostgreSQL connection saturation, and lock contention when interpreting a run.

Each submitted order rotates deterministically across the seeded users and inventory items using k6's per-scenario iteration number, independent of VU allocation. Defaults match the PostgreSQL seed: users 1–12 and items 1–12. If the seed IDs or prices change, pass the available IDs and matching prices, for example:

```bash
k6 run \
  -e LOAD_TEST_USER_IDS=1,2,3,4 \
  -e LOAD_TEST_ITEM_IDS=10,11,12 \
  -e LOAD_TEST_ITEM_PRICES=10:20,11:25,12:30 \
  asset/scripts/load_testing.js
```

Every item is ordered with quantity 1. The configured item price is sent in the request so the Order and Payment services charge the same amount as the Inventory catalog.

The test measures Coordinator `POST /ecomm/order` latency from k6. End-to-end terminal workflow duration is calculated after the load from `coordinator_workflows.terminal_at - submitted_at`; `terminal_at` is recorded when all participant acknowledgments for either the commit or abort decision arrive. The report includes combined terminal latency and separate latency distributions for `COMPLETED` and `ABORTED` outcomes. Non-terminal workflows have no terminal latency at the snapshot. This uses database-clock timestamps without per-order status polling or polling traffic during the measured phases. Phase assignment uses database submission time and k6 phase markers; the runner and PostgreSQL host should have synchronized clocks. The workflow CSV is retained in each results directory for audit.

PostgreSQL's connection limit is shared by all four application databases. Each app pod caps its Hikari pool at five connections; at the configured replica caps of 5/5/3/3, the app-side ceiling is 80 connections, leaving headroom under PostgreSQL's default 100. `run.sh` checks these deployment settings before measurement and records PostgreSQL's configured limit and client connection count before and after the run.

## 1. Deploy probe and application changes

Rebuild and deploy the updated Java services and apply the Deployment manifests. The manifests use a startup probe, a liveness probe, and a readiness probe. Readiness includes Spring readiness state; the database-backed services also require a healthy database. Liveness only checks the application process, so a dependency outage does not cause restart loops. Kafka connectivity is not added to readiness in this implementation; Kafka failures show up in end-to-end completion and lag data instead.

The collector uses Pod creation, container start, and Ready-condition timestamps from Kubernetes. It also watches Pod lifecycle Events (`Pulling`, `Pulled`, and `Created`) and retains their source timestamps. Each service publishes `application.main.timestamp` immediately on entry to `main()` and `application.ready.timestamp` at Spring's `ApplicationReadyEvent`; the observer reads both from the management port (8085). This separates container-to-runtime startup, Spring/application initialization, and probe-to-Pod-ready time.

## 2. Start the Kubernetes observer before the load

The observer watches Pods, Pod lifecycle Events, Deployments, KEDA ScaledObjects, and the HPAs managed by KEDA. `run.sh` starts it before creating application Pods, so it can capture initial image pulls as well as later scale-up Pods. It also reads per-pod startup timestamps. Run it inside the cluster so the pod IPs are reachable:

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

The observer has read-only access to lifecycle/scaling resources in namespace `ecomm`. Prometheus also receives node discovery and kubelet metrics read access to scrape `/metrics/cadvisor` over HTTPS using its ServiceAccount token. The runner uses the current kubeconfig identity for `kubectl top` samples. The observer's application request is a GET to each pod's Actuator ready-timestamp metric on port 8085.

## 3. Run k6

For local Java services:

```bash
k6 run \
  --out json=results/k6.jsonl \
  --summary-export=results/k6-summary.json \
  asset/scripts/load_testing.js
```

The standalone k6 command measures submission latency only. The timestamp-based workflow completion report is produced by `run.sh`, which can query the Coordinator database after the load; standalone k6 does not collect that database snapshot.

For a standalone Kubernetes k6 run, only the Coordinator endpoint is needed. `run.sh` additionally captures workflow timestamps directly from PostgreSQL after the measured load and post-load drain.

```bash
k6 run \
  --out json=results/k6.jsonl \
  --summary-export=results/k6-summary.json \
  -e COORDINATOR_URL=http://<node-ip>:<coordinator-node-port> \
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

The report includes per-phase target and observed request rates, started iterations, request latency (mean, p50, p95, p99), HTTP/submission error rates, counts and terminal latency for both `COMPLETED` and `ABORTED` workflows, non-terminal workflow counts, dropped k6 iterations and VU counts, pod lifecycle percentiles, and scale-up timing per KEDA-managed HPA. SLO analysis groups Coordinator POST latencies into fixed 30-second windows with at least 30 requests. A window violates the default `<500 ms` p95 SLO when p95 is `>=500 ms`. An episode begins at the first violating window and ends at the first immediately following eligible good window, with recovery confirmed by two consecutive good windows. Its duration is quantized to 30-second window boundaries; the report also gives confirmation time, violating-window count, and p95 values. Missing/under-sampled windows are not treated as passing and cannot be bridged to claim recovery. Set `SLO_START_PHASE` to align windows with that k6 phase's scheduled start and exclude earlier requests; use this for the explicit spike phase. The report distinguishes `no_violation` (zero seconds), `recovered`, `unresolved` (lower bound only), and `insufficient_data`. This duration measures time p95 is actually above the SLO, not time from spike start. If the workflow drain reaches its timeout, the report exposes non-terminal workflow counts and terminal-latency coverage.

The startup report separates:

- Pod creation to Kubernetes Ready condition.
- Container start to application `main()` entry (JVM/native runtime startup).
- Application `main()` entry to Spring `ApplicationReadyEvent` (Spring/application initialization).
- Spring ready event to Pod Ready condition (probe/dependency and scheduling interval).

Each `run.sh` result directory also contains `pod-startup-phases.csv`, one raw timestamp row per Pod created during measurement. Where Kubernetes Events are emitted, it additionally records image-pull start/end and container-created timestamps. Event timestamps are operational Kubernetes records and can be delayed, aggregated, or unavailable on a particular cluster; retain the source `kubernetes-events.jsonl` and treat a missing phase value as missing rather than zero.

See [STARTUP_METRICS.md](STARTUP_METRICS.md) for the exact source, calculation, precision, and interpretation of every startup ledger/report metric, including the distinction between image-pull operation duration and duration including waiting.

## Paper result hierarchy

Present results in this order: (1) SLO-violation episode duration after the load spike, (2) startup phase breakdown and fresh-Pod Ready time, (3) autoscaler reaction and Pod provisioning as explanatory results, (4) mitigation effects, and (5) a compact steady-state parity table. Keep request/workflow latency and resource use in that final supporting subsection (or appendix if space is tight). State that steady-state parity is supported only after comparing repeated runs with the same rate, configuration, and warm application state. The generated report preserves all raw per-phase values but labels the steady-state group as supporting evidence and names the primary outcomes separately.

The scale report defines `T0` as the first load marker emitted immediately before a phase's first POST. `T1` is the first observed HPA external metric value above its configured target when that metric was below target at phase start. `T2` is the collector-observed desired-replica increase, `T3` is the new pod's API creation timestamp, and `T4` is its Ready-condition transition timestamp. This gives `T1−T0` for metric reaction, `T2−T0` for replica decision, `T2−T1` for decision delay, `T4−T2` for pod provisioning, and `T4−T0` for total response. T1 is left unset when the HPA metric sample is unavailable or already above target at phase start. The collector watch receipt time is used for T1/T2, so their resolution is affected by API watch delivery and cluster clock synchronization. Use synchronized clocks on the k6 runner, cluster nodes, and API control plane.

## Interpretation and limits

Check `load_generator.all_scheduled_arrivals_started` and per-phase arrival delivery before interpreting throughput. Any dropped k6 iteration means the load generator did not sustain the configured arrival rate. The test sends requests through the Coordinator. Coordinator scales on submission rate or response-topic lag; Order, Inventory, and Payment scale on their Kafka lag. KEDA may correctly leave a worker at one replica if it keeps up. Coordinator and Order can scale to five replicas; Inventory and Payment can scale to three.

The coordinator stores workflow state durably in PostgreSQL and publishes Kafka commands through a transactional outbox. Any Coordinator replica can process a response. Compare completion rate, consumer lag, and database contention when interpreting scale-out; a larger replica count alone does not prove that the system processed more orders.

Use repeated runs and report the exact image digest, resource requests/limits, replica bounds, node type/count, Kubernetes/KEDA versions, and test profile. A single run is not enough to make a strong performance claim. The observer emits raw timestamped JSONL so the percentile and event calculations can be independently reviewed.

The JSON report includes observed image IDs, deployment resource settings, and HPA bounds. Capture the remaining cluster context with the run artifacts:

```bash
kubectl version -o yaml > results/kubernetes-version.yaml
kubectl get nodes -o wide > results/nodes.txt
kubectl -n ecomm get scaledobjects,hpa,deployments -o yaml > results/scaling-configuration.yaml
```
