#!/usr/bin/env python3
"""Summarize k6 JSON output and Kubernetes JSONL lifecycle observations."""

import argparse
import csv
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


PHASE_DURATIONS_SECONDS = {
    "baseline": 120,
    "transition_baseline_to_burst": 30,
    "transition_burst_to_peak": 30,
    "transition_peak_to_recovery": 30,
    "peak_burst": 300,
    "recovery": 120,
}


def phase_duration_seconds(phase):
    if phase in PHASE_DURATIONS_SECONDS:
        return PHASE_DURATIONS_SECONDS[phase]
    if phase.startswith("burst_") and phase.endswith("_rps"):
        return 300
    return None


def parse_time(value):
    if not value:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000, timezone.utc)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def epoch_ms(value):
    dt = parse_time(value)
    return dt.timestamp() * 1000 if dt else None


def percentile(values, percentage):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentage / 100
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def summarize_latency(values):
    return {
        "samples": len(values),
        "mean": sum(values) / len(values) if values else None,
        "p50": percentile(values, 50),
        "p95": percentile(values, 95),
        "p99": percentile(values, 99),
    }


def summarize_runtime_metrics(records):
    grouped = defaultdict(list)
    metric_units = {
        "container_cpu_cores": "cores",
        "container_memory_working_set_bytes": "bytes",
        "container_cpu_throttled_period_ratio": "ratio",
        "kafka_consumer_group_lag": "messages",
        "kafka_consumer_group_partition_lag": "messages",
        "kafka_consumer_group_lagging_partition_count": "partitions",
        "kafka_topic_partition_count": "partitions",
    }
    for record in records:
        metric = record.get("metric")
        labels = record.get("labels", {})
        if not metric:
            continue
        label_key = tuple(sorted((key, value) for key, value in labels.items()))
        grouped[(metric, label_key)].append((float(record["value"]), record.get("observed_at")))
    result = {}
    for (metric, label_key), observations in sorted(grouped.items()):
        values = [item[0] for item in observations]
        peak = max(observations, key=lambda item: item[0])
        result.setdefault(metric, {"unit": metric_units.get(metric), "series": []})
        result[metric]["series"].append({
            "labels": dict(label_key),
            "samples": len(values),
            "mean": sum(values) / len(values),
            "p95": percentile(values, 95),
            "max": max(values),
            "max_observed_at": peak[1],
        })
    return result


def summarize_postgres_connections(path):
    if not path or not Path(path).exists():
        return {"samples": 0, "maximum_connections": None, "maximum_utilization_percent": None}
    samples = []
    with open(path, newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            try:
                samples.append({
                    "sampled_at_utc": row["sampled_at_utc"],
                    "max_connections": int(row["max_connections"]),
                    "client_connections": int(row["client_connections"]),
                    "active_connections": int(row["active_connections"]),
                })
            except (KeyError, ValueError):
                continue
    peak = max(samples, key=lambda item: item["client_connections"], default=None)
    return {
        "samples": len(samples),
        "maximum_connections": peak["client_connections"] if peak else None,
        "maximum_utilization_percent": (
            peak["client_connections"] * 100 / peak["max_connections"]
            if peak and peak["max_connections"] else None
        ),
        "peak_sample": peak,
    }


def summarize_node_resources(path):
    if not path or not Path(path).exists():
        return {"samples": 0, "peak_cpu_percent": None, "peak_memory_percent": None}
    rows = []
    with open(path, newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            try:
                rows.append({
                    **row,
                    "cpu_percent_value": float(row["cpu_percent"].rstrip("%")),
                    "memory_percent_value": float(row["memory_percent"].rstrip("%")),
                })
            except (KeyError, ValueError):
                continue
    peak_cpu = max(rows, key=lambda item: item["cpu_percent_value"], default=None)
    peak_memory = max(rows, key=lambda item: item["memory_percent_value"], default=None)
    return {
        "samples": len(rows),
        "peak_cpu_percent": peak_cpu["cpu_percent_value"] if peak_cpu else None,
        "peak_cpu_sample": peak_cpu,
        "peak_memory_percent": peak_memory["memory_percent_value"] if peak_memory else None,
        "peak_memory_sample": peak_memory,
    }


def read_workflows(path, phase_starts, phase_durations=None):
    """Group durable Coordinator rows by their k6 submission phase."""
    result = defaultdict(list)
    if path is None:
        return result

    with open(path, newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            submitted_at = parse_time(row.get("submitted_at"))
            if not submitted_at:
                continue
            for phase, start in phase_starts.items():
                duration = (phase_durations or {}).get(phase) or phase_duration_seconds(phase)
                if duration is None:
                    continue
                end = start.timestamp() + duration
                if start.timestamp() <= submitted_at.timestamp() < end:
                    terminal_at = parse_time(row.get("terminal_at"))
                    # Keep old completed-only CSVs readable; they cannot provide
                    # abort latency because the old schema did not timestamp ABORTED.
                    if terminal_at is None:
                        terminal_at = parse_time(row.get("completed_at"))
                    state = row.get("state")
                    result[phase].append({
                        "correlation_id": row.get("correlation_id"),
                        "state": state,
                        "submitted_at": submitted_at,
                        "terminal_at": terminal_at,
                        "terminal_latency_ms": (
                            (terminal_at - submitted_at).total_seconds() * 1000
                            if state in {"COMPLETED", "ABORTED"} and terminal_at else None
                        ),
                    })
                    break
    return result


def load_json_lines(path, ignore_non_json=False, ignored_counter=None):
    with open(path, encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as exc:
                if ignore_non_json and not line.lstrip().startswith("{"):
                    if ignored_counter is not None:
                        ignored_counter["count"] += 1
                    continue
                raise ValueError(f"Invalid JSON in {path}:{line_number}: {exc}") from exc


def read_k6(path):
    phase_starts = {}
    phase_durations = {}
    phase_latencies = defaultdict(list)
    phase_failures = defaultdict(list)
    phase_e2e_latencies = defaultdict(list)
    phase_lookup_latencies = defaultdict(list)
    phase_submission_errors = defaultdict(list)
    phase_completion_errors = defaultdict(list)
    phase_submitted = defaultdict(int)
    phase_completed = defaultdict(int)
    phase_markers = defaultdict(int)
    dropped_by_scenario = defaultdict(int)
    max_active_vus = 0
    max_initialized_vus = 0
    all_http_samples = []
    for point in load_json_lines(path):
        if point.get("type") != "Point":
            continue
        metric = point.get("metric")
        data = point.get("data", {})
        tags = data.get("tags") or {}
        sample_time = parse_time(data.get("time"))
        if not sample_time:
            continue
        phase = tags.get("phase")
        if metric == "load_phase_marker" and phase:
            scheduled_start = parse_time(tags.get("phase_start_utc")) or sample_time
            phase_starts[phase] = min(phase_starts.get(phase, scheduled_start), scheduled_start)
            if tags.get("phase_duration_seconds"):
                phase_durations[phase] = float(tags["phase_duration_seconds"])
            phase_markers[phase] += int(data.get("value", 0))
        elif metric == "http_req_duration" and tags.get("endpoint") == "order-submission":
            latency = float(data.get("value", 0))
            all_http_samples.append((sample_time, latency))
            if phase:
                phase_latencies[phase].append(latency)
                # Also use POST samples as phase boundaries. This lets the
                # analyzer recover runs whose k6 output omitted custom counters.
                phase_starts[phase] = min(phase_starts.get(phase, sample_time), sample_time)
        elif metric == "http_req_failed" and tags.get("endpoint") == "order-submission":
            if phase:
                phase_failures[phase].append(bool(data.get("value", 0)))
        elif metric == "order_end_to_end_latency_ms" and phase:
            phase_e2e_latencies[phase].append(float(data.get("value", 0)))
        elif metric == "order_status_lookup_latency_ms" and phase:
            phase_lookup_latencies[phase].append(float(data.get("value", 0)))
        elif metric == "order_submission_failed" and phase:
            phase_submission_errors[phase].append(bool(data.get("value", 0)))
        elif metric == "order_completion_failed" and phase:
            phase_completion_errors[phase].append(bool(data.get("value", 0)))
        elif metric == "orders_submitted" and phase:
            phase_submitted[phase] += int(data.get("value", 0))
        elif metric == "orders_completed" and phase:
            phase_completed[phase] += int(data.get("value", 0))
        elif metric == "dropped_iterations":
            scenario = tags.get("scenario")
            dropped_by_scenario[scenario or "unattributed"] += int(data.get("value", 0))
        elif metric == "vus":
            max_active_vus = max(max_active_vus, int(data.get("value", 0)))
        elif metric == "vus_max":
            max_initialized_vus = max(max_initialized_vus, int(data.get("value", 0)))
    return (phase_starts, phase_latencies, phase_failures, all_http_samples,
            phase_e2e_latencies, phase_lookup_latencies, phase_submission_errors,
            phase_completion_errors, phase_submitted, phase_completed, phase_markers,
            dropped_by_scenario, max_active_vus, max_initialized_vus, phase_durations)


def summarize_requests(phase_starts, phase_latencies, phase_failures, phase_e2e_latencies,
                       phase_lookup_latencies, phase_submission_errors, phase_completion_errors,
                       phase_submitted, phase_completed, phase_markers, phase_rates, phase_workflows,
                       phase_durations=None):
    result = {}
    phases = set(phase_starts) | set(phase_latencies) | set(phase_workflows)
    for phase in sorted(phases, key=lambda name: phase_starts.get(name, datetime.max.replace(tzinfo=timezone.utc))):
        values = phase_latencies.get(phase, [])
        failures = phase_failures.get(phase, [])
        workflows = phase_workflows.get(phase, [])
        terminal_latencies = [
            workflow["terminal_latency_ms"] for workflow in workflows
            if workflow["terminal_latency_ms"] is not None
        ]
        completed_latencies = [
            workflow["terminal_latency_ms"] for workflow in workflows
            if workflow["state"] == "COMPLETED" and workflow["terminal_latency_ms"] is not None
        ]
        aborted_latencies = [
            workflow["terminal_latency_ms"] for workflow in workflows
            if workflow["state"] == "ABORTED" and workflow["terminal_latency_ms"] is not None
        ]
        workflow_completed = sum(workflow["state"] == "COMPLETED" for workflow in workflows)
        workflow_aborted = sum(workflow["state"] == "ABORTED" for workflow in workflows)
        workflow_pending = len(workflows) - workflow_completed - workflow_aborted
        duration = (phase_durations or {}).get(phase) or phase_duration_seconds(phase)
        target_rate = phase_rates.get(phase)
        scheduled_iterations = target_rate * duration if target_rate is not None and duration else None
        result[phase] = {
            "samples": len(values),
            "throughput_requests_per_second": len(values) / duration if duration else None,
            "target_rate_requests_per_second": target_rate,
            "scheduled_iterations": scheduled_iterations,
            "phase_marker_iterations": phase_markers.get(phase, 0),
            "arrival_delivery_percent": (
                len(values) * 100 / scheduled_iterations if scheduled_iterations else None
            ),
            "latency_ms": {
                "mean": sum(values) / len(values) if values else None,
                "p50": percentile(values, 50),
                "p95": percentile(values, 95),
                "p99": percentile(values, 99),
            },
            "http_failure_rate": sum(failures) / len(failures) if failures else None,
            "orders_submitted": len(workflows),
            "k6_successful_submission_responses": phase_submitted.get(phase, 0),
            "orders_completed": workflow_completed,
            "orders_aborted": workflow_aborted,
            "workflows_non_terminal_at_snapshot": workflow_pending,
            "terminal_latency_coverage_percent": (
                len(terminal_latencies) * 100 / len(workflows) if workflows else None
            ),
            "submission_failure_rate": (
                sum(phase_submission_errors.get(phase, [])) / len(phase_submission_errors[phase])
                if phase_submission_errors.get(phase) else None
            ),
            "workflow_non_terminal_rate_at_snapshot": (
                workflow_pending / len(workflows) if workflows else None
            ),
            "end_to_end_terminal_latency_ms": summarize_latency(terminal_latencies),
            "completed_order_terminal_latency_ms": summarize_latency(completed_latencies),
            "aborted_order_terminal_latency_ms": summarize_latency(aborted_latencies),
            "phase_start_utc": phase_starts[phase].isoformat() if phase in phase_starts else None,
        }
    return result


def slo_episodes(samples, window_seconds, target_ms, minimum_requests, stable_windows):
    if not samples:
        return [], []
    samples.sort(key=lambda sample: sample[0])
    origin = samples[0][0].timestamp()
    windows = defaultdict(list)
    for sample_time, latency in samples:
        bucket = int((sample_time.timestamp() - origin) // window_seconds)
        windows[bucket].append(latency)

    measured = []
    for bucket, values in sorted(windows.items()):
        if len(values) < minimum_requests:
            continue
        measured.append({
            "bucket": bucket,
            "start_seconds": bucket * window_seconds,
            "p95_ms": percentile(values, 95),
            "requests": len(values),
            "violating": percentile(values, 95) >= target_ms,
        })

    episodes = []
    index = 0
    while index < len(measured):
        if not measured[index]["violating"]:
            index += 1
            continue
        first = measured[index]
        next_index = index + 1
        while (next_index < len(measured)
               and measured[next_index]["bucket"] == measured[next_index - 1]["bucket"] + 1
               and measured[next_index]["violating"]):
            next_index += 1
        recovery_index = None
        for candidate in range(next_index, len(measured) - stable_windows + 1):
            candidates = measured[candidate:candidate + stable_windows]
            consecutive = all(
                candidates[offset]["bucket"] == candidates[offset - 1]["bucket"] + 1
                for offset in range(1, len(candidates))
            )
            if consecutive and all(not item["violating"] for item in candidates):
                recovery_index = candidate
                break
        violation_start = origin + first["start_seconds"]
        if recovery_index is None:
            end = None
            duration = None
            recovered = False
            confirmed_at = None
        else:
            end = origin + measured[recovery_index]["start_seconds"]
            duration = end - violation_start
            recovered = True
            confirmed_at = origin + (
                measured[recovery_index + stable_windows - 1]["start_seconds"] + window_seconds
            )
        episodes.append({
            "violation_start_utc": datetime.fromtimestamp(violation_start, timezone.utc).isoformat(),
            "recovered": recovered,
            "slo_recovered_at_utc": datetime.fromtimestamp(end, timezone.utc).isoformat() if end else None,
            "recovery_confirmation_observed_at_utc": (
                datetime.fromtimestamp(confirmed_at, timezone.utc).isoformat() if confirmed_at else None
            ),
            "violation_duration_seconds": duration,
            "worst_window_p95_ms": max(item["p95_ms"] for item in measured[index:next_index]),
            "recovery_requires_consecutive_good_windows": stable_windows,
        })
        index = max(next_index, recovery_index + stable_windows if recovery_index is not None else next_index)
    return episodes, [
        {
            "window_start_seconds_from_first_request": item["start_seconds"],
            "requests": item["requests"],
            "p95_ms": item["p95_ms"],
            "violates_slo": item["violating"],
        }
        for item in measured
    ]


def read_kubernetes(path):
    ignored_lines = {"count": 0}
    records = list(load_json_lines(path, ignore_non_json=True, ignored_counter=ignored_lines))
    pods = defaultdict(list)
    hpas = defaultdict(list)
    deployments = defaultdict(list)
    app_ready_metrics = {}
    scaledobjects = {}
    collector_gaps = []
    runtime_metric_samples = []
    runtime_metric_errors = []
    for record in records:
        kind = record.get("resource")
        if kind == "pod":
            pods[record.get("uid")].append(record)
        elif kind == "hpa":
            hpas[record.get("target_name")].append(record)
        elif kind == "deployment":
            deployments[record.get("name")].append(record)
        elif kind == "scaledobject":
            scaledobjects[record.get("name")] = record
        elif record.get("record_type") == "application_ready_metric":
            app_ready_metrics[record.get("pod_uid")] = record
        elif record.get("record_type") == "collector_gap":
            collector_gaps.append(record)
        elif record.get("record_type") == "runtime_metric_sample":
            runtime_metric_samples.append(record)
        elif record.get("record_type") == "runtime_metric_error":
            runtime_metric_errors.append(record)
    for history in (*pods.values(), *hpas.values(), *deployments.values()):
        history.sort(key=lambda item: parse_time(item.get("observed_at")) or datetime.min.replace(tzinfo=timezone.utc))
    return (pods, hpas, deployments, app_ready_metrics, scaledobjects, collector_gaps,
            ignored_lines["count"], runtime_metric_samples, runtime_metric_errors)


def summarize_pods(pods, app_ready_metrics, measurement_start):
    pod_to_ready = defaultdict(list)
    app_to_ready = defaultdict(list)
    app_to_app_ready = defaultdict(list)
    app_image_ids = defaultdict(set)
    unready = 0
    ready_pods = 0
    ready_pods_without_app_timestamp = 0
    for uid, history in pods.items():
        first = history[0]
        latest = history[-1]
        created_ms = epoch_ms(first.get("creation_timestamp"))
        if created_ms is None or created_ms < measurement_start.timestamp() * 1000:
            continue
        ready_records = [item for item in history if item.get("ready") and item.get("ready_transition_at")]
        first_ready_at = min(
            (item["ready_transition_at"] for item in ready_records),
            key=lambda value: parse_time(value),
            default=None,
        )
        app = latest.get("labels", {}).get("app") or first.get("labels", {}).get("app")
        for container in latest.get("containers", []):
            if container.get("image_id"):
                app_image_ids[app].add(container["image_id"])
        if first_ready_at and created_ms:
            ready_ms = epoch_ms(first_ready_at)
            if ready_ms is not None and ready_ms >= created_ms:
                pod_to_ready[app].append((ready_ms - created_ms) / 1000)
                ready_pods += 1
        if latest.get("event_type") != "DELETED" and not latest.get("ready"):
            unready += 1

        ready_metric = app_ready_metrics.get(uid)
        if first_ready_at and not ready_metric:
            ready_pods_without_app_timestamp += 1
        container_starts = [
            epoch_ms(container.get("started_at"))
            for container in latest.get("containers", [])
            if container.get("started_at")
        ]
        if ready_metric and container_starts:
            started_ms = min(container_starts)
            app_ready_ms = ready_metric.get("application_ready_timestamp_ms")
            if app_ready_ms and app_ready_ms >= started_ms:
                app_to_app_ready[app].append((app_ready_ms - started_ms) / 1000)
                ready_ms = epoch_ms(first_ready_at)
                if ready_ms and ready_ms >= app_ready_ms:
                    app_to_ready[app].append((ready_ms - app_ready_ms) / 1000)

    def stats(values):
        return {
            "samples": len(values),
            "mean_seconds": sum(values) / len(values) if values else None,
            "median_seconds": percentile(values, 50),
            "p95_seconds": percentile(values, 95),
            "p99_seconds": percentile(values, 99),
        }

    apps = {}
    for app in sorted(set(pod_to_ready) | set(app_to_app_ready) | set(app_to_ready)):
        apps[app or "unknown"] = {
            "pod_creation_to_kubernetes_ready": stats(pod_to_ready[app]),
            "container_start_to_spring_application_ready": stats(app_to_app_ready[app]),
            "spring_application_ready_to_pod_ready": stats(app_to_ready[app]),
            "observed_image_ids": sorted(app_image_ids[app]),
        }
    return {
        "apps": apps,
        "pods_not_ready_at_collection_end": unready,
        "ready_pods_in_measurement": ready_pods,
        "ready_pods_without_application_ready_timestamp": ready_pods_without_app_timestamp,
    }


def deployment_matches_pod(selector, pod):
    labels = pod.get("labels", {})
    return bool(selector) and all(labels.get(key) == value for key, value in selector.items())


def metric_over_target(hpa_record):
    """Return whether any HPA metric is above its configured target, when comparable."""
    targets = {}
    for item in hpa_record.get("metric_targets", []):
        if item.get("type") != "External":
            continue
        external = item.get("external", {})
        name = external.get("metric", {}).get("name")
        target = external.get("target", {})
        value = target.get("averageValue") or target.get("value")
        try:
            if name and value is not None:
                targets[name] = float(value)
        except (ValueError, TypeError):
            continue

    for item in hpa_record.get("current_metrics", []):
        if item.get("type") != "External":
            continue
        external = item.get("external", {})
        name = external.get("metric", {}).get("name")
        current = external.get("current", {})
        value = current.get("averageValue") or current.get("value")
        try:
            if name in targets and value is not None and float(value) > targets[name]:
                return True
        except (ValueError, TypeError):
            continue
    return False


def summarize_scaling(phase_starts, pods, hpas, deployments, phase_durations=None):
    output = {}
    deployment_selectors = {}
    for deployment_name, history in deployments.items():
        if history:
            deployment_selectors[deployment_name] = history[-1].get("selector", {})
    unique_pods = []
    for history in pods.values():
        if not history:
            continue
        merged = dict(history[-1])
        ready_records = [item for item in history if item.get("ready") and item.get("ready_transition_at")]
        merged["first_ready_transition_at"] = min(
            (item["ready_transition_at"] for item in ready_records),
            key=lambda value: parse_time(value),
            default=None,
        )
        unique_pods.append(merged)

    for phase, phase_start in sorted(phase_starts.items(), key=lambda item: item[1]):
        phase_data = {}
        start_ms = phase_start.timestamp() * 1000
        phase_duration = (phase_durations or {}).get(phase) or phase_duration_seconds(phase)
        end_ms = start_ms + phase_duration * 1000 if phase_duration is not None else None
        for deployment_name, hpa_history in hpas.items():
            hpa_history.sort(key=lambda item: parse_time(item.get("observed_at")) or datetime.min.replace(tzinfo=timezone.utc))
            before = [item for item in hpa_history if epoch_ms(item.get("observed_at")) <= start_ms]
            baseline = before[-1]["desired_replicas"] if before else (hpa_history[0]["desired_replicas"] if hpa_history else 0)
            trigger_active_before = metric_over_target(before[-1]) if before else None
            trigger_events = [
                item for item in hpa_history
                if epoch_ms(item.get("observed_at")) >= start_ms
                and (end_ms is None or epoch_ms(item.get("observed_at")) < end_ms)
                and metric_over_target(item)
            ]
            trigger_event = trigger_events[0] if trigger_events and not trigger_active_before else None
            changes = [
                item for item in hpa_history
                if epoch_ms(item.get("observed_at")) >= start_ms
                and (end_ms is None or epoch_ms(item.get("observed_at")) < end_ms)
                and item.get("desired_replicas", 0) > baseline
            ]
            if not changes:
                phase_data[deployment_name] = {
                    "scaled_up": False,
                    "baseline_desired_replicas": baseline,
                    "trigger_already_above_target_at_phase_start": trigger_active_before,
                    "hpa_metric_crossed_target_observed_at": trigger_event.get("observed_at") if trigger_event else None,
                    "hpa_metric_reaction_lag_seconds": (
                        (epoch_ms(trigger_event.get("observed_at")) - start_ms) / 1000 if trigger_event else None
                    ),
                    "hpa_reaction_lag_seconds": None,
                    "pod_provisioning_seconds": None,
                    "scale_up_responsiveness_seconds": None,
                }
                continue

            scale_event = changes[0]
            t2_ms = epoch_ms(scale_event.get("observed_at"))
            selector = deployment_selectors.get(deployment_name, {})
            new_pods = []
            for pod in unique_pods:
                if not deployment_matches_pod(selector, pod):
                    continue
                created_ms = epoch_ms(pod.get("creation_timestamp"))
                ready_ms = epoch_ms(pod.get("first_ready_transition_at"))
                if created_ms and created_ms >= t2_ms:
                    new_pods.append((created_ms, ready_ms, pod))
            new_pods.sort(key=lambda item: item[0])
            created = new_pods[0][0] if new_pods else None
            newly_ready = [item[1] for item in new_pods if item[1] and item[1] >= t2_ms]
            first_ready = min(newly_ready) if newly_ready else None
            phase_data[deployment_name] = {
                "scaled_up": True,
                "baseline_desired_replicas": baseline,
                "trigger_already_above_target_at_phase_start": trigger_active_before,
                "hpa_metric_crossed_target_observed_at": trigger_event.get("observed_at") if trigger_event else None,
                "hpa_metric_reaction_lag_seconds": (
                    (epoch_ms(trigger_event.get("observed_at")) - start_ms) / 1000 if trigger_event else None
                ),
                "desired_replicas_after_scale": scale_event.get("desired_replicas"),
                "hpa_desired_replica_change_observed_at": scale_event.get("observed_at"),
                "hpa_reaction_lag_seconds": (t2_ms - start_ms) / 1000,
                "hpa_decision_delay_seconds": (
                    (t2_ms - epoch_ms(trigger_event.get("observed_at"))) / 1000 if trigger_event else None
                ),
                "first_new_pod_creation_at": datetime.fromtimestamp(created / 1000, timezone.utc).isoformat() if created else None,
                "pod_creation_delay_seconds": (created - t2_ms) / 1000 if created else None,
                "first_new_pod_ready_at": datetime.fromtimestamp(first_ready / 1000, timezone.utc).isoformat() if first_ready else None,
                "pod_provisioning_seconds": (first_ready - t2_ms) / 1000 if first_ready else None,
                "scale_up_responsiveness_seconds": (first_ready - start_ms) / 1000 if first_ready else None,
            }
        output[phase] = phase_data
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--k6", required=True, help="k6 JSON output file from --out json=...")
    parser.add_argument("--kubernetes", required=True, help="collector JSONL output")
    parser.add_argument("--workflows", help="CSV snapshot of Coordinator workflow timestamps from PostgreSQL")
    parser.add_argument("--postgres-connections", help="CSV time series of PostgreSQL connection samples")
    parser.add_argument("--node-resources", help="CSV time series from kubectl top nodes")
    parser.add_argument("--output", default="load-test-summary.json")
    parser.add_argument("--slo-p95-ms", type=float, default=500)
    parser.add_argument("--slo-window-seconds", type=int, default=30)
    parser.add_argument("--slo-min-requests", type=int, default=30)
    parser.add_argument("--slo-stable-windows", type=int, default=2)
    parser.add_argument("--burst-rate", type=int, default=15)
    parser.add_argument("--peak-rate", type=int, default=50)
    parser.add_argument("--target-rps", type=int, default=0)
    parser.add_argument("--warmup-rate", type=int, default=3)
    parser.add_argument("--preallocated-vus", type=int, default=300)
    parser.add_argument("--http-timeout-seconds", type=int, default=5)
    parser.add_argument("--vu-safety-factor", type=float, default=1.2)
    args = parser.parse_args()
    if args.slo_window_seconds <= 0 or args.slo_min_requests <= 0 or args.slo_stable_windows <= 0:
        parser.error("SLO window, minimum request count, and stable-window count must be positive")

    (phase_starts, phase_latencies, phase_failures, request_samples, phase_e2e_latencies,
     phase_lookup_latencies, phase_submission_errors, phase_completion_errors,
     phase_submitted, phase_completed, phase_markers, dropped_by_scenario,
     max_active_vus, max_initialized_vus, phase_durations) = read_k6(args.k6)
    if not phase_starts:
        raise SystemExit("No measured-phase POST samples found in k6 JSON output; check the test script and endpoint tags.")
    (pods, hpas, deployments, app_ready_metrics, scaledobjects, collector_gaps,
     collector_log_lines_ignored, runtime_metric_samples, runtime_metric_errors) = read_kubernetes(args.kubernetes)
    phase_workflows = read_workflows(args.workflows, phase_starts, phase_durations)
    violations, slo_windows = slo_episodes(
        request_samples,
        args.slo_window_seconds,
        args.slo_p95_ms,
        args.slo_min_requests,
        args.slo_stable_windows,
    )
    if args.target_rps > 0:
        phase_rates = {
            "warmup": args.warmup_rate,
            "ramp_up": None,
            "target_hold": args.target_rps,
            "ramp_down": None,
        }
    else:
        burst_phase = f"burst_{args.burst_rate}_rps"
        phase_rates = {
            "baseline": 3,
            "transition_baseline_to_burst": None,
            burst_phase: args.burst_rate,
            "transition_burst_to_peak": None,
            "peak_burst": args.peak_rate,
            "transition_peak_to_recovery": None,
            "recovery": 3,
        }
    total_dropped_iterations = sum(dropped_by_scenario.values())
    request_attempts_by_phase = {
        phase: len(values) for phase, values in phase_latencies.items()
    }
    summary = {
        "method": {
            "latency_source": "k6 POST /ecomm/order http_req_duration samples only",
            "terminal_latency_source": (
                "Coordinator coordinator_workflows.submitted_at and terminal_at from PostgreSQL; "
                "includes both COMPLETED and ABORTED terminal outcomes, with no status polling during measured load."
            ),
            "workflow_snapshot_source": "Coordinator PostgreSQL CSV captured after load and post-load drain",
            "percentile_method": "linear interpolation over sorted observations",
            "throughput_denominator": "configured phase duration",
            "slo": {
                "definition": f"rolling fixed {args.slo_window_seconds}s request windows with at least {args.slo_min_requests} POST samples; p95 must be < {args.slo_p95_ms}ms",
                "recovery_rule": f"{args.slo_stable_windows} consecutive eligible windows below the SLO",
                "limitations": "Windows with too few samples are omitted; recovery time is backdated to the first qualifying good window and reported only after the configured consecutive-good-window rule is confirmed.",
            },
            "timing_note": "k6 runner, Kubernetes API observer, and cluster nodes should have synchronized clocks (NTP). HPA reaction time uses observer receipt time and is bounded by watch delivery/measurement resolution.",
        },
        "requests_by_phase": summarize_requests(
            phase_starts, phase_latencies, phase_failures, phase_e2e_latencies,
            phase_lookup_latencies, phase_submission_errors, phase_completion_errors,
            phase_submitted, phase_completed, phase_markers, phase_rates, phase_workflows,
            phase_durations,
        ),
        "load_generator": {
            "executor": "one ramping-arrival-rate scenario with a fixed preallocated VU pool",
            "scenario": "load_profile",
            "preallocated_vus": args.preallocated_vus,
            "max_vus": args.preallocated_vus,
            "maximum_active_vus_observed": max_active_vus,
            "maximum_initialized_vus_observed": max_initialized_vus,
            "http_timeout_seconds": args.http_timeout_seconds,
            "vu_safety_factor": args.vu_safety_factor,
            "dropped_iterations_by_scenario": dict(dropped_by_scenario),
            "total_dropped_iterations": total_dropped_iterations,
            "all_scheduled_arrivals_started": total_dropped_iterations == 0,
            "phase_request_attempts": request_attempts_by_phase,
            "target_rates_requests_per_second": phase_rates,
            "phase_durations_seconds": phase_durations,
        },
        "slo_violations": violations,
        "slo_windows": slo_windows,
        "pod_startup": summarize_pods(pods, app_ready_metrics, min(phase_starts.values())),
        "scale_up": summarize_scaling(
            {phase: start for phase, start in phase_starts.items()
             if phase in phase_rates and (phase_rates[phase] is not None or phase == "ramp_up")},
            pods, hpas, deployments, phase_durations,
        ),
        "deployment_configuration": {
            name: history[-1]
            for name, history in deployments.items()
            if history
        },
        "hpa_configuration": {
            name: {
                "target_name": history[-1].get("target_name"),
                "min_replicas": history[-1].get("min_replicas"),
                "max_replicas": history[-1].get("max_replicas"),
                "metric_targets": history[-1].get("metric_targets", []),
            }
            for name, history in hpas.items()
            if history
        },
        "keda_scaledobjects_observed": sorted(scaledobjects.keys()),
        "collector_gaps": collector_gaps,
        "runtime_metrics": summarize_runtime_metrics(runtime_metric_samples),
        "runtime_metric_sample_errors": runtime_metric_errors,
        "postgres_connections": summarize_postgres_connections(args.postgres_connections),
        "node_resources": summarize_node_resources(args.node_resources),
        "collector_non_json_log_lines_ignored": collector_log_lines_ignored,
        "source_counts": {
            "pods": len(pods),
            "hpas": len(hpas),
            "deployments": len(deployments),
            "spring_application_ready_timestamps": len(app_ready_metrics),
            "runtime_metric_samples": len(runtime_metric_samples),
            "runtime_metric_sample_errors": len(runtime_metric_errors),
        },
    }
    output = Path(args.output)
    output.write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
