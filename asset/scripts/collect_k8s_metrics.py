#!/usr/bin/env python3
"""Record timestamped, read-only Kubernetes lifecycle and scaler observations as JSONL."""

import argparse
import json
import signal
import sys
import threading
import time
from datetime import datetime, timezone
from urllib.error import URLError
from urllib.request import urlopen

from kubernetes import client, config, watch
from kubernetes.client.rest import ApiException


APP_PORTS = {
    "coordinator": 8081,
    "order": 8083,
    "inventory": 8082,
    "payment": 8084,
}


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def iso(value):
    return value.isoformat().replace("+00:00", "Z") if value else None


class Recorder:
    def __init__(self, args):
        self.args = args
        self.stop_event = threading.Event()
        self.write_lock = threading.Lock()
        self.app_ready_recorded = set()
        self.output = sys.stdout if args.output == "-" else open(args.output, "a", encoding="utf-8", buffering=1)

    def write(self, record):
        record["observed_at"] = utc_now()
        with self.write_lock:
            self.output.write(json.dumps(record, separators=(",", ":")) + "\n")
            self.output.flush()

    @staticmethod
    def pod_record(obj, event_type):
        metadata = obj.metadata
        status = obj.status
        conditions = status.conditions or []
        ready_condition = next((item for item in conditions if item.type == "Ready"), None)
        container_statuses = []
        for item in status.container_statuses or []:
            state = item.state
            running = state.running if state else None
            container_statuses.append({
                "name": item.name,
                "image": item.image,
                "image_id": item.image_id,
                "ready": item.ready,
                "started": item.started,
                "started_at": iso(running.started_at) if running else None,
                "restart_count": item.restart_count,
            })
        return {
            "record_type": "kubernetes",
            "resource": "pod",
            "event_type": event_type,
            "namespace": metadata.namespace,
            "name": metadata.name,
            "uid": metadata.uid,
            "resource_version": metadata.resource_version,
            "creation_timestamp": iso(metadata.creation_timestamp),
            "labels": metadata.labels or {},
            "owner_references": [
                {"kind": owner.kind, "name": owner.name, "uid": owner.uid}
                for owner in (metadata.owner_references or [])
            ],
            "pod_ip": status.pod_ip,
            "phase": status.phase,
            "ready": ready_condition.status == "True" if ready_condition else False,
            "ready_transition_at": iso(ready_condition.last_transition_time) if ready_condition else None,
            "containers": container_statuses,
        }

    def capture_app_ready_metric(self, record):
        app = record["labels"].get("app")
        port = APP_PORTS.get(app)
        pod_uid = record.get("uid")
        if not port or not record.get("pod_ip") or not pod_uid or pod_uid in self.app_ready_recorded:
            return
        if not record.get("ready"):
            return

        url = f"http://{record['pod_ip']}:{port}/actuator/metrics/application.ready.timestamp"
        try:
            with urlopen(url, timeout=self.args.http_timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
            value = next(
                (item["value"] for item in payload.get("measurements", []) if item.get("statistic") == "VALUE"),
                0,
            )
            if value and value > 0:
                self.app_ready_recorded.add(pod_uid)
                self.write({
                    "record_type": "application_ready_metric",
                    "namespace": record["namespace"],
                    "pod_name": record["name"],
                    "pod_uid": pod_uid,
                    "app": app,
                    "application_ready_timestamp_ms": int(value),
                    "pod_ip": record["pod_ip"],
                })
        except (URLError, TimeoutError, json.JSONDecodeError, KeyError, StopIteration) as exc:
            print(f"Could not read app-ready metric from {record['name']}: {exc}", file=sys.stderr)

    def watch_resource(self, resource, list_call, normalize):
        resource_version = None
        while not self.stop_event.is_set():
            watcher = watch.Watch()
            try:
                if not resource_version:
                    snapshot = list_call(namespace=self.args.namespace)
                    if isinstance(snapshot, dict):
                        metadata = snapshot.get("metadata", {})
                        items = snapshot.get("items", [])
                        resource_version = metadata.get("resourceVersion")
                    else:
                        metadata = snapshot.metadata
                        items = snapshot.items or []
                        resource_version = metadata.resource_version
                    for obj in items:
                        record = normalize(obj, "SNAPSHOT")
                        self.write(record)
                        if resource == "pod":
                            self.capture_app_ready_metric(record)
                stream = watcher.stream(
                    list_call,
                    namespace=self.args.namespace,
                    resource_version=resource_version,
                    timeout_seconds=30,
                )
                for event in stream:
                    if self.stop_event.is_set():
                        break
                    event_type = event.get("type", "UNKNOWN")
                    obj = event.get("object")
                    if not obj:
                        continue
                    if isinstance(obj, dict):
                        resource_version = obj.get("metadata", {}).get("resourceVersion", resource_version)
                    else:
                        resource_version = obj.metadata.resource_version or resource_version
                    if event_type == "BOOKMARK":
                        continue
                    record = normalize(obj, event_type)
                    self.write(record)
                    if resource == "pod":
                        self.capture_app_ready_metric(record)
            except ApiException as exc:
                if exc.status == 404 and resource == "scaledobject":
                    print("KEDA ScaledObject CRD not found; skipping ScaledObject watch.", file=sys.stderr)
                    return
                if exc.status == 410:
                    resource_version = None
                    self.write({
                        "record_type": "collector_gap",
                        "resource": resource,
                        "reason": "watch_resource_version_expired; resumed from a fresh snapshot",
                    })
                print(f"Kubernetes watch for {resource} failed: {exc}", file=sys.stderr)
                self.stop_event.wait(2)
            except Exception as exc:  # reconnect on API/network interruptions
                if not self.stop_event.is_set():
                    self.write({
                        "record_type": "collector_gap",
                        "resource": resource,
                        "reason": f"watch_interrupted: {type(exc).__name__}",
                    })
                    print(f"Kubernetes watch for {resource} interrupted: {exc}", file=sys.stderr)
                    self.stop_event.wait(2)
            finally:
                watcher.stop()

    @staticmethod
    def deployment_record(obj, event_type):
        metadata = obj.metadata
        status = obj.status
        selector = obj.spec.selector.match_labels if obj.spec.selector else {}
        template_containers = obj.spec.template.spec.containers or []
        serializer = client.ApiClient()
        return {
            "record_type": "kubernetes",
            "resource": "deployment",
            "event_type": event_type,
            "namespace": metadata.namespace,
            "name": metadata.name,
            "uid": metadata.uid,
            "observed_generation": status.observed_generation,
            "selector": selector or {},
            "desired_replicas": obj.spec.replicas,
            "replicas": status.replicas or 0,
            "ready_replicas": status.ready_replicas or 0,
            "available_replicas": status.available_replicas or 0,
            "containers": [
                {
                    "name": container.name,
                    "image": container.image,
                    "resources": serializer.sanitize_for_serialization(container.resources) if container.resources else {},
                }
                for container in template_containers
            ],
        }

    @staticmethod
    def hpa_record(obj, event_type):
        metadata = obj.metadata
        target = obj.spec.scale_target_ref
        status = obj.status
        serializer = client.ApiClient()
        return {
            "record_type": "kubernetes",
            "resource": "hpa",
            "event_type": event_type,
            "namespace": metadata.namespace,
            "name": metadata.name,
            "target_kind": target.kind,
            "target_name": target.name,
            "current_replicas": status.current_replicas or 0,
            "desired_replicas": status.desired_replicas or 0,
            "min_replicas": obj.spec.min_replicas if obj.spec.min_replicas is not None else 1,
            "max_replicas": obj.spec.max_replicas,
            "current_metrics": serializer.sanitize_for_serialization(status.current_metrics or []),
            "metric_targets": serializer.sanitize_for_serialization(obj.spec.metrics or []),
            "conditions": [
                {"type": item.type, "status": item.status, "reason": item.reason, "message": item.message}
                for item in (status.conditions or [])
            ],
        }

    @staticmethod
    def scaledobject_record(obj, event_type):
        metadata = obj.get("metadata", {})
        spec = obj.get("spec", {})
        status = obj.get("status", {})
        return {
            "record_type": "kubernetes",
            "resource": "scaledobject",
            "event_type": event_type,
            "namespace": metadata.get("namespace"),
            "name": metadata.get("name"),
            "scale_target_name": spec.get("scaleTargetRef", {}).get("name"),
            "min_replica_count": spec.get("minReplicaCount"),
            "max_replica_count": spec.get("maxReplicaCount"),
            "triggers": [trigger.get("type") for trigger in spec.get("triggers", [])],
            "conditions": status.get("conditions", []),
            "health": status.get("health", {}),
        }

    def run(self):
        signal.signal(signal.SIGTERM, lambda *_: self.stop_event.set())
        config.load_incluster_config() if self.args.in_cluster else config.load_kube_config()
        core = client.CoreV1Api()
        apps = client.AppsV1Api()
        autoscaling = client.AutoscalingV2Api()
        custom = client.CustomObjectsApi()

        streams = [
            ("pod", core.list_namespaced_pod, self.pod_record),
            ("deployment", apps.list_namespaced_deployment, self.deployment_record),
            ("hpa", autoscaling.list_namespaced_horizontal_pod_autoscaler, self.hpa_record),
            (
                "scaledobject",
                lambda **kwargs: custom.list_namespaced_custom_object(
                    group="keda.sh", version="v1alpha1", plural="scaledobjects", **kwargs
                ),
                self.scaledobject_record,
            ),
        ]
        threads = [
            threading.Thread(target=self.watch_resource, args=entry, daemon=True, name=f"watch-{entry[0]}")
            for entry in streams
        ]
        for thread in threads:
            thread.start()

        print(f"Recording namespace {self.args.namespace} to {self.args.output}. Press Ctrl-C to stop.", file=sys.stderr)
        try:
            while any(thread.is_alive() for thread in threads):
                time.sleep(0.5)
        except KeyboardInterrupt:
            self.stop_event.set()
        finally:
            self.stop_event.set()
            for thread in threads:
                thread.join(timeout=2)
            if self.output is not sys.stdout:
                self.output.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="ecomm")
    parser.add_argument("--output", default="kubernetes-events.jsonl", help="JSONL file path, or '-' for stdout")
    parser.add_argument("--in-cluster", action="store_true", help="use the pod's ServiceAccount credentials")
    parser.add_argument("--http-timeout", type=float, default=2.0)
    args = parser.parse_args()
    Recorder(args).run()


if __name__ == "__main__":
    main()
