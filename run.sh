#!/usr/bin/env bash

# Deploy the ECommerce stack and run one captured load/scaling experiment.
# This script intentionally does not delete the namespace: doing so would also
# delete StatefulSet claims and can destroy the PostgreSQL/Kafka test data.

set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="ecomm"
RESULTS_ROOT="${RESULTS_ROOT:-${ROOT_DIR}/results}"
SLO_P95_MS="${SLO_P95_MS:-500}"
SLO_WINDOW_SECONDS="${SLO_WINDOW_SECONDS:-30}"
SLO_MIN_REQUESTS="${SLO_MIN_REQUESTS:-30}"
BURST_RATE="${BURST_RATE:-15}"
PEAK_RATE="${PEAK_RATE:-50}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-50}"
MAX_VUS="${MAX_VUS:-100}"
WORKFLOW_DRAIN_TIMEOUT_SECONDS="${WORKFLOW_DRAIN_TIMEOUT_SECONDS:-300}"
WORKFLOW_DRAIN_POLL_SECONDS="${WORKFLOW_DRAIN_POLL_SECONDS:-5}"
LOAD_TEST_USER_IDS="${LOAD_TEST_USER_IDS:-1,2,3,4,5,6,7,8,9,10,11,12}"
LOAD_TEST_ITEM_IDS="${LOAD_TEST_ITEM_IDS:-1,2,3,4,5,6,7,8,9,10,11,12}"
LOAD_TEST_ITEM_PRICES="${LOAD_TEST_ITEM_PRICES:-1:10,2:10,3:10,4:10,5:10,6:10,7:20,8:20,9:20,10:20,11:20,12:20}"
METRICS_COLLECTOR_IMAGE="${METRICS_COLLECTOR_IMAGE:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
IMAGE_REPOSITORY_PREFIX="${IMAGE_REPOSITORY_PREFIX:-siddhesh1712}"

DEPLOY_STACK=1
DEPLOY_ONLY=0
ASSUME_YES=0
PIDS=()
COLLECTOR_STARTED=0
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="${RESULTS_ROOT}/${RUN_ID}"

usage() {
  cat <<'EOF'
Usage: ./run.sh [--yes] [--deploy-only] [--skip-deploy]

Default: apply infrastructure and app manifests, refresh the app deployments,
start the Kubernetes metrics observer, run the four-phase k6 workload, and
write a JSON metrics report under results/<UTC timestamp>/.

Options:
  --yes          Skip the prompt confirming the current kubectl context.
  --deploy-only  Deploy the stack and verify readiness, but do not run k6.
  --skip-deploy  Use an already deployed stack; still run observer and k6.
  -h, --help     Show this help.

Environment:
  METRICS_COLLECTOR_IMAGE  Registry image for non-Minikube/non-Kind clusters.
  BURST_RATE / PEAK_RATE   k6 phase rates (defaults: 15 and 50 iterations/s).
  WORKFLOW_DRAIN_TIMEOUT_SECONDS  Post-load wait for terminal workflows (default: 300).
  RESULTS_ROOT             Output root (default: ./results).
  LOAD_TEST_USER_IDS       Comma-separated seeded user IDs (default: 1-12).
  LOAD_TEST_ITEM_IDS       Comma-separated seeded inventory IDs (default: 1-12).
  LOAD_TEST_ITEM_PRICES    Comma-separated id:price pairs matching inventory.
  IMAGE_TAG                App image tag to deploy (default: latest; e.g. latest-graalvm).
  IMAGE_REPOSITORY_PREFIX  App image repository prefix (default: siddhesh1712).
EOF
}

for arg in "$@"; do
  case "$arg" in
    --yes) ASSUME_YES=1 ;;
    --deploy-only) DEPLOY_ONLY=1 ;;
    --skip-deploy) DEPLOY_STACK=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
  esac
done
if (( DEPLOY_ONLY && ! DEPLOY_STACK )); then
  echo "--deploy-only cannot be combined with --skip-deploy." >&2
  exit 2
fi

log() { printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

cleanup() {
  local pid
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "${PIDS[@]:-}"; do
    wait "$pid" 2>/dev/null || true
  done
  if (( COLLECTOR_STARTED )); then
    kubectl -n "$NAMESPACE" scale deployment/load-metrics-collector --replicas=0 >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command '$1' is not installed or not on PATH."
}

require_command kubectl
require_command python3
require_command curl
if (( ! DEPLOY_ONLY )); then
  require_command docker
  require_command k6
fi
kubectl cluster-info >/dev/null 2>&1 || die "kubectl cannot reach the selected cluster. Check your kubeconfig/context."

CONTEXT="$(kubectl config current-context)"
log "Selected Kubernetes context: ${CONTEXT}"
log "Namespace: ${NAMESPACE}"
if [[ "$CONTEXT" != *minikube* && "$CONTEXT" != kind-* && -z "$METRICS_COLLECTOR_IMAGE" && $DEPLOY_ONLY -eq 0 ]]; then
  die "For this cluster, set METRICS_COLLECTOR_IMAGE to a registry image the nodes can pull (for example your-registry/load-metrics-collector:tag). The script will build and push it."
fi

# The Kubernetes node advertises host memory, but the Minikube Docker
# container has its own cgroup limit. Prevent a run when that container is
# already near its cap; the previous run hit probe, Kafka, KEDA, and API timeouts.
if [[ "$CONTEXT" == *minikube* && $DEPLOY_ONLY -eq 0 ]] && command -v docker >/dev/null 2>&1; then
  MINIKUBE_MEM_PERCENT="$(docker stats --no-stream --format '{{.MemPerc}}' minikube 2>/dev/null | sed 's/%//' || true)"
  if [[ -n "$MINIKUBE_MEM_PERCENT" ]] && awk -v percent="$MINIKUBE_MEM_PERCENT" 'BEGIN { exit !(percent >= 90) }'; then
    die "Minikube is using ${MINIKUBE_MEM_PERCENT}% of its Docker memory limit. Increase Minikube memory or free host memory before running the load test; this level caused probe and Kubernetes API timeouts."
  fi
fi

kubectl get crd scaledobjects.keda.sh >/dev/null 2>&1 || die "KEDA's ScaledObject CRD is missing. Install KEDA in this cluster before deploying the autoscaling resources."

if (( ! ASSUME_YES )); then
  read -r -p "Deploy/run the experiment against context '${CONTEXT}' in namespace '${NAMESPACE}'? [y/N] " answer
  [[ "$answer" == "y" || "$answer" == "Y" || "$answer" == "yes" || "$answer" == "YES" ]] || die "Cancelled. No resources were changed."
fi

if (( DEPLOY_STACK )); then
  log "Creating namespace if needed (existing namespace and persistent data are preserved)."
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

  log "Applying PostgreSQL resources."
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/db-secrets.yml"
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/postgresql-initdb-config.yml"
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/postgresql-statefulset.yml"

  # The old Kafka StatefulSet used a ZooKeeper-era data claim named data-kafka-0.
  # KRaft gets a fresh claim named kraft-data-kafka-0; retain the old claim as a
  # backup and replace only the broker/controller workload when migrating.
  CURRENT_KAFKA_IMAGE="$(kubectl -n "$NAMESPACE" get statefulset kafka -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
  CURRENT_KAFKA_CLAIM="$(kubectl -n "$NAMESPACE" get statefulset kafka -o jsonpath='{.spec.volumeClaimTemplates[0].metadata.name}' 2>/dev/null || true)"
  if [[ -n "$CURRENT_KAFKA_IMAGE" && ( "$CURRENT_KAFKA_IMAGE" != "apache/kafka:latest" || "$CURRENT_KAFKA_CLAIM" != "kraft-data" ) ]]; then
    log "Replacing the ZooKeeper-era Kafka StatefulSet with the KRaft broker. Its old PVC will be retained; Kafka topic data is not migrated."
    kubectl -n "$NAMESPACE" delete statefulset kafka --wait=true
    if kubectl -n "$NAMESPACE" get pod kafka-0 >/dev/null 2>&1; then
      kubectl -n "$NAMESPACE" wait --for=delete pod/kafka-0 --timeout=3m
    fi
  fi
  kubectl -n "$NAMESPACE" delete statefulset/zookeeper service/zookeeper service/zookeeper-service --ignore-not-found=true --wait=true

  log "Applying the single-node Apache Kafka KRaft StatefulSet."
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/kafka.yml"

  log "Waiting for the database and KRaft Kafka StatefulSets."
  kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=5m
  kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout=5m

  # PostgreSQL init scripts run only on a fresh data directory. This small
  # idempotent Job also creates coordinator_db when the existing PVC is reused.
  kubectl -n "$NAMESPACE" delete job ensure-coordinator-db --ignore-not-found=true --wait=true
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/coordinator-db-job.yml"
  kubectl -n "$NAMESPACE" wait --for=condition=complete job/ensure-coordinator-db --timeout=2m

  log "Ensuring application Kafka topics have at least five partitions."
  kubectl -n "$NAMESPACE" delete job kafka-topic-partitions --ignore-not-found=true --wait=true
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/statefulsets-deployment/kafka-topics-job.yml"
  kubectl -n "$NAMESPACE" wait --for=condition=complete job/kafka-topic-partitions --timeout=3m

  log "Applying monitoring, tracing, and Kafka exporter resources."
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/monitoring/"
  kubectl -n "$NAMESPACE" rollout restart deployment/prometheus
  kubectl -n "$NAMESPACE" rollout status deployment/prometheus --timeout=5m
  kubectl -n "$NAMESPACE" rollout status deployment/kafka-exporter --timeout=5m

  log "Applying the four application Deployments and Services."
  # Remove the previous KEDA HPAs during the rollout so they do not compete
  # with the controlled one-replica baseline for this experiment.
  kubectl -n "$NAMESPACE" delete -f "$ROOT_DIR/service-deployment/autoscaling.yml" --ignore-not-found=true --wait=true
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/service-deployment/coordinator-deployment.yml"
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/service-deployment/order-deployment.yml"
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/service-deployment/inventory-deployment.yml"
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/service-deployment/payment-deployment.yml"

  # Select the strategy-tagged images built and optionally pushed by
  # scripts/build-images.sh. Defaults preserve the existing :latest workflow.
  kubectl -n "$NAMESPACE" set image deployment/coordinator-deployment \
    coordinator="$IMAGE_REPOSITORY_PREFIX/coordinator:$IMAGE_TAG"
  kubectl -n "$NAMESPACE" set image deployment/order-deployment \
    order="$IMAGE_REPOSITORY_PREFIX/order:$IMAGE_TAG"
  kubectl -n "$NAMESPACE" set image deployment/inventory-deployment \
    inventory="$IMAGE_REPOSITORY_PREFIX/inventory:$IMAGE_TAG"
  kubectl -n "$NAMESPACE" set image deployment/payment-deployment \
    payment="$IMAGE_REPOSITORY_PREFIX/payment:$IMAGE_TAG"

  # Restarting forces imagePullPolicy: Always to fetch the selected images.
  kubectl -n "$NAMESPACE" rollout restart deployment/coordinator-deployment deployment/order-deployment deployment/inventory-deployment deployment/payment-deployment

  log "Waiting for application rollouts."
  kubectl -n "$NAMESPACE" rollout status deployment/coordinator-deployment --timeout=5m
  kubectl -n "$NAMESPACE" rollout status deployment/order-deployment --timeout=5m
  kubectl -n "$NAMESPACE" rollout status deployment/inventory-deployment --timeout=5m
  kubectl -n "$NAMESPACE" rollout status deployment/payment-deployment --timeout=5m

  log "Applying KEDA autoscaling objects."
  kubectl -n "$NAMESPACE" scale deployment/coordinator-deployment deployment/order-deployment deployment/inventory-deployment deployment/payment-deployment --replicas=1
  kubectl apply -n "$NAMESPACE" -f "$ROOT_DIR/service-deployment/autoscaling.yml"

  if (( DEPLOY_ONLY )); then
    log "Deploy-only finished. Check pods, PVCs, ScaledObjects, and HPAs with kubectl before running the experiment."
    exit 0
  fi
else
  log "Skipping deployment as requested."
  kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || die "Namespace '$NAMESPACE' does not exist."
  kubectl -n "$NAMESPACE" get deployment coordinator-deployment order-deployment inventory-deployment payment-deployment >/dev/null
fi

log "Waiting for application Deployments to be available before measurement."
for deployment in coordinator-deployment order-deployment inventory-deployment payment-deployment; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$deployment" --timeout=5m
done

mkdir -p "$RESULTS_DIR"
log "Saving experiment artifacts to $RESULTS_DIR"
kubectl version -o yaml >"$RESULTS_DIR/kubernetes-version.yaml"
kubectl get nodes -o wide >"$RESULTS_DIR/nodes.txt"
kubectl -n "$NAMESPACE" get pods -o wide >"$RESULTS_DIR/pods-before.txt"
kubectl -n "$NAMESPACE" get scaledobjects,hpa,deployments -o yaml >"$RESULTS_DIR/scaling-configuration.yaml"

if [[ -z "$METRICS_COLLECTOR_IMAGE" ]]; then
  METRICS_COLLECTOR_IMAGE="load-metrics-collector:${RUN_ID}"
fi
log "Building metrics observer image: $METRICS_COLLECTOR_IMAGE"
docker build -f "$ROOT_DIR/asset/scripts/metrics-collector.Dockerfile" -t "$METRICS_COLLECTOR_IMAGE" "$ROOT_DIR"
if [[ "$CONTEXT" == *minikube* ]]; then
  minikube image load "$METRICS_COLLECTOR_IMAGE"
elif [[ "$CONTEXT" == kind-* ]]; then
  kind load docker-image "$METRICS_COLLECTOR_IMAGE"
else
  docker push "$METRICS_COLLECTOR_IMAGE"
fi

kubectl apply -f "$ROOT_DIR/asset/scripts/metrics-collector-rbac.yaml"
kubectl apply -f "$ROOT_DIR/asset/scripts/metrics-collector-deployment.yaml"
kubectl -n "$NAMESPACE" set image deployment/load-metrics-collector collector="$METRICS_COLLECTOR_IMAGE"
kubectl -n "$NAMESPACE" rollout status deployment/load-metrics-collector --timeout=3m
COLLECTOR_STARTED=1

COLLECTOR_POD=""
for attempt in $(seq 1 60); do
  # A Deployment rollout can briefly leave an older, not-yet-started pod in
  # the selector result. Only follow a Running pod, then verify it is Ready.
  COLLECTOR_POD="$(kubectl -n "$NAMESPACE" get pods -l app=load-metrics-collector --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  [[ -n "$COLLECTOR_POD" ]] && break
  sleep 1
done
[[ -n "$COLLECTOR_POD" ]] || die "Metrics observer has no Running pod. Check kubectl -n $NAMESPACE get pods and describe the observer pod."
kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$COLLECTOR_POD" --timeout=2m
kubectl -n "$NAMESPACE" logs -f "$COLLECTOR_POD" >"$RESULTS_DIR/kubernetes-events.jsonl" 2>"$RESULTS_DIR/collector-stderr.log" &
PIDS+=("$!")

for attempt in $(seq 1 30); do
  [[ -s "$RESULTS_DIR/kubernetes-events.jsonl" ]] && break
  sleep 1
done
if [[ ! -s "$RESULTS_DIR/kubernetes-events.jsonl" ]]; then
  echo "Metrics observer has not emitted any JSONL records; see $RESULTS_DIR/collector-stderr.log" >&2
  die "Stopping before k6 so the run cannot produce a report with missing Kubernetes metrics."
fi

if [[ "$CONTEXT" == *minikube* ]]; then
  # Use NodePorts for the load generator. Port-forward opens one API-server
  # tunnel to a pod and produced stream timeouts/reset connections at load.
  MINIKUBE_IP="${MINIKUBE_IP:-$(minikube ip 2>/dev/null || true)}"
  COORDINATOR_NODE_PORT="$(kubectl -n "$NAMESPACE" get service coordinator-service -o jsonpath='{.spec.ports[0].nodePort}')"
  [[ -n "$MINIKUBE_IP" ]] || die "Could not determine Minikube IP; set MINIKUBE_IP explicitly."
  [[ -n "$COORDINATOR_NODE_PORT" ]] || die "Coordinator Service must have a NodePort."
  COORDINATOR_URL="${COORDINATOR_URL:-http://${MINIKUBE_IP}:${COORDINATOR_NODE_PORT}}"
else
  log "Starting port-forwards for non-Minikube cluster access."
  kubectl -n "$NAMESPACE" port-forward service/coordinator-service 8081:80 >"$RESULTS_DIR/coordinator-port-forward.log" 2>&1 &
  PIDS+=("$!")
  COORDINATOR_URL="${COORDINATOR_URL:-http://127.0.0.1:8081}"
fi
log "Coordinator load-test URL: $COORDINATOR_URL"

log "Running submission-only k6 phases: baseline, ${BURST_RATE} RPS burst, ${PEAK_RATE} RPS peak, recovery."
K6_START_AT="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
set +e
k6 run \
  --out "json=${RESULTS_DIR}/k6.jsonl" \
  --summary-export="${RESULTS_DIR}/k6-summary.json" \
  -e "BURST_RATE=${BURST_RATE}" \
  -e "PEAK_RATE=${PEAK_RATE}" \
  -e "PREALLOCATED_VUS=${PREALLOCATED_VUS}" \
  -e "MAX_VUS=${MAX_VUS}" \
  -e "LOAD_TEST_USER_IDS=${LOAD_TEST_USER_IDS}" \
  -e "LOAD_TEST_ITEM_IDS=${LOAD_TEST_ITEM_IDS}" \
  -e "LOAD_TEST_ITEM_PRICES=${LOAD_TEST_ITEM_PRICES}" \
  -e "COORDINATOR_URL=${COORDINATOR_URL}" \
  "$ROOT_DIR/asset/scripts/load_testing.js"
K6_STATUS=$?
set -e
K6_END_AT="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"

log "Waiting for workflows from this run to reach a terminal state (up to ${WORKFLOW_DRAIN_TIMEOUT_SECONDS}s); no order-status polling is sent to the services."
drain_started_at="$(date +%s)"
pending_workflows=0
while :; do
  pending_workflows="$(kubectl exec -n "$NAMESPACE" postgres-0 -- \
    sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -U "$POSTGRES_USER" "$@"' sh \
    -d coordinator_db -tA -v ON_ERROR_STOP=1 \
    -v "run_start=$K6_START_AT" -v "run_end=$K6_END_AT" \
    -c "SELECT COUNT(*) FROM coordinator_workflows WHERE submitted_at >= :'run_start'::timestamptz AND submitted_at <= :'run_end'::timestamptz AND state NOT IN ('COMPLETED', 'ABORTED');" | tr -d '[:space:]')"
  [[ "$pending_workflows" =~ ^[0-9]+$ ]] || die "Could not read pending workflow count from coordinator_db."
  (( pending_workflows == 0 )) && break
  now="$(date +%s)"
  if (( now - drain_started_at >= WORKFLOW_DRAIN_TIMEOUT_SECONDS )); then
    log "Drain timeout reached with $pending_workflows workflows still non-terminal. Capturing their database status now."
    break
  fi
  log "$pending_workflows workflows are still non-terminal; checking again in ${WORKFLOW_DRAIN_POLL_SECONDS}s."
  sleep "$WORKFLOW_DRAIN_POLL_SECONDS"
done

log "Exporting Coordinator workflow timestamps from PostgreSQL."
kubectl exec -n "$NAMESPACE" postgres-0 -- \
  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -U "$POSTGRES_USER" "$@"' sh \
  -d coordinator_db --csv -v ON_ERROR_STOP=1 \
  -v "run_start=$K6_START_AT" -v "run_end=$K6_END_AT" \
  -c "SELECT correlation_id, state, submitted_at, completed_at FROM coordinator_workflows WHERE submitted_at >= :'run_start'::timestamptz AND submitted_at <= :'run_end'::timestamptz ORDER BY submitted_at;" \
  >"$RESULTS_DIR/coordinator-workflows.csv"

log "Capturing final cluster state."
if ! kubectl -n "$NAMESPACE" get pods -o wide >"$RESULTS_DIR/pods-after.txt"; then
  log "WARNING: Kubernetes API was unavailable for the final pod snapshot."
fi
if ! kubectl -n "$NAMESPACE" get scaledobjects,hpa,deployments -o yaml >"$RESULTS_DIR/scaling-configuration-after.yaml"; then
  log "WARNING: Kubernetes API was unavailable for the final scaling snapshot."
fi

# Stop the log stream cleanly so all captured JSONL records are flushed.
for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
for pid in "${PIDS[@]:-}"; do wait "$pid" 2>/dev/null || true; done
PIDS=()
kubectl -n "$NAMESPACE" scale deployment/load-metrics-collector --replicas=0 >/dev/null || \
  log "WARNING: Could not scale down the metrics collector; clean it up after the API recovers."
COLLECTOR_STARTED=0

log "Calculating the report."
[[ -s "$RESULTS_DIR/k6.jsonl" ]] || die "k6 output is missing; cannot generate the experiment report."
[[ -s "$RESULTS_DIR/kubernetes-events.jsonl" ]] || die "Kubernetes observer output is missing; cannot generate the experiment report."
python3 "$ROOT_DIR/asset/scripts/analyze_load_test.py" \
  --k6 "$RESULTS_DIR/k6.jsonl" \
  --kubernetes "$RESULTS_DIR/kubernetes-events.jsonl" \
  --workflows "$RESULTS_DIR/coordinator-workflows.csv" \
  --output "$RESULTS_DIR/metrics-report.json" \
  --slo-p95-ms "$SLO_P95_MS" \
  --slo-window-seconds "$SLO_WINDOW_SECONDS" \
  --slo-min-requests "$SLO_MIN_REQUESTS"

log "Coordinator workflow timestamp snapshot: $RESULTS_DIR/coordinator-workflows.csv"
log "Experiment complete. Report: $RESULTS_DIR/metrics-report.json"
log "Raw k6 data: $RESULTS_DIR/k6.jsonl"
log "Kubernetes observer data: $RESULTS_DIR/kubernetes-events.jsonl"
if (( K6_STATUS != 0 )); then
  echo "k6 returned status $K6_STATUS (often a threshold failure); the report was still generated." >&2
  exit "$K6_STATUS"
fi
