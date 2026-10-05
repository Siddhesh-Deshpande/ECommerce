#!/usr/bin/env python3
"""Summarize k6 JSON output and Kubernetes JSONL lifecycle observations."""

import argparse
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


PHASE_DURATIONS_SECONDS = {
    "baseline": 120,
    "burst_15_rps": 300,
    "peak_burst": 300,
    "recovery": 120,
}
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
    phase_latencies = defaultdict(list)
    phase_failures = defaultdict(list)
    phase_e2e_latencies = defaultdict(list)
    phase_lookup_latencies = defaultdict(list)
    phase_submission_errors = defaultdict(list)
    phase_completion_errors = defaultdict(list)
    phase_submitted = defaultdict(int)
    phase_completed = defaultdict(int)
    phase_dropped = defaultdict(int)
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
            phase_starts[phase] = min(phase_starts.get(phase, sample_time), sample_time)
        elif metric == "http_req_duration" and tags.get("endpoint") == "order-submission":
            latency = float(data.get("value", 0))
            all_http_samples.append((sample_time, latency))
            if phase:
                phase_latencies[phase].append(latency)
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
            if scenario:
                phase_dropped[scenario] += int(data.get("value", 0))
    return (phase_starts, phase_latencies, phase_failures, all_http_samples,
            phase_e2e_latencies, phase_lookup_latencies, phase_submission_errors,
            phase_completion_errors, phase_submitted, phase_completed, phase_dropped)


def summarize_requests(phase_starts, phase_latencies, phase_failures, phase_e2e_latencies,
                       phase_lookup_latencies, phase_submission_errors, phase_completion_errors,
                       phase_submitted, phase_completed, phase_dropped):
    result = {}
    for phase, values in sorted(phase_latencies.items(), key=lambda item: phase_starts.get(item[0], datetime.max.replace(tzinfo=timezone.utc))):
        failures = phase_failures.get(phase, [])
        duration = PHASE_DURATIONS_SECONDS.get(phase)
        result[phase] = {
            "samples": len(values),
            "throughput_requests_per_second": len(values) / duration if duration else None,
            "latency_ms": {
                "mean": sum(values) / len(values) if values else None,
                "p50": percentile(values, 50),
                "p95": percentile(values, 95),
                "p99": percentile(values, 99),
            },
            "http_failure_rate": sum(failures) / len(failures) if failures else None,
            "orders_submitted": phase_submitted.get(phase, 0),
            "orders_completed": phase_completed.get(phase, 0),
            "orders_not_completed_within_timeout": max(
                0, phase_submitted.get(phase, 0) - phase_completed.get(phase, 0)
            ),
            "submission_failure_rate": (
                sum(phase_submission_errors.get(phase, [])) / len(phase_submission_errors[phase])
                if phase_submission_errors.get(phase) else None
            ),
            "completion_timeout_rate": (
                sum(phase_completion_errors.get(phase, [])) / len(phase_completion_errors[phase])
                if phase_completion_errors.get(phase) else None
            ),
            "end_to_end_completion_latency_ms": {
                "samples": len(phase_e2e_latencies.get(phase, [])),
                "mean": (sum(phase_e2e_latencies[phase]) / len(phase_e2e_latencies[phase])
                         if phase_e2e_latencies.get(phase) else None),
                "p50": percentile(phase_e2e_latencies.get(phase, []), 50),
                "p95": percentile(phase_e2e_latencies.get(phase, []), 95),
                "p99": percentile(phase_e2e_latencies.get(phase, []), 99),
            },
            "status_lookup_latency_ms": {
                "samples": len(phase_lookup_latencies.get(phase, [])),
                "p50": percentile(phase_lookup_latencies.get(phase, []), 50),
                "p95": percentile(phase_lookup_latencies.get(phase, []), 95),
                "p99": percentile(phase_lookup_latencies.get(phase, []), 99),
            },
            "dropped_iterations": phase_dropped.get(phase, 0),
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
    for history in (*pods.values(), *hpas.values(), *deployments.values()):
        history.sort(key=lambda item: parse_time(item.get("observed_at")) or datetime.min.replace(tzinfo=timezone.utc))
    return pods, hpas, deployments, app_ready_metrics, scaledobjects, collector_gaps, ignored_lines["count"]


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


def summarize_scaling(phase_starts, pods, hpas, deployments):
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
        for deployment_name, hpa_history in hpas.items():
            hpa_history.sort(key=lambda item: parse_time(item.get("observed_at")) or datetime.min.replace(tzinfo=timezone.utc))
            before = [item for item in hpa_history if epoch_ms(item.get("observed_at")) <= start_ms]
            baseline = before[-1]["desired_replicas"] if before else (hpa_history[0]["desired_replicas"] if hpa_history else 0)
            trigger_active_before = metric_over_target(before[-1]) if before else None
            trigger_events = [
                item for item in hpa_history
                if epoch_ms(item.get("observed_at")) >= start_ms and metric_over_target(item)
            ]
            trigger_event = trigger_events[0] if trigger_events and not trigger_active_before else None
            changes = [
                item for item in hpa_history
                if epoch_ms(item.get("observed_at")) >= start_ms and item.get("desired_replicas", 0) > baseline
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
    parser.add_argument("--output", default="load-test-summary.json")
    parser.add_argument("--slo-p95-ms", type=float, default=500)
    parser.add_argument("--slo-window-seconds", type=int, default=30)
    parser.add_argument("--slo-min-requests", type=int, default=30)
    parser.add_argument("--slo-stable-windows", type=int, default=2)
    args = parser.parse_args()
    if args.slo_window_seconds <= 0 or args.slo_min_requests <= 0 or args.slo_stable_windows <= 0:
        parser.error("SLO window, minimum request count, and stable-window count must be positive")

    (phase_starts, phase_latencies, phase_failures, request_samples, phase_e2e_latencies,
     phase_lookup_latencies, phase_submission_errors, phase_completion_errors,
     phase_submitted, phase_completed, phase_dropped) = read_k6(args.k6)
    if not phase_starts:
        raise SystemExit("No k6 load_phase_marker samples found; verify k6 JSON output and phase tags.")
    (pods, hpas, deployments, app_ready_metrics, scaledobjects, collector_gaps,
     collector_log_lines_ignored) = read_kubernetes(args.kubernetes)
    violations, slo_windows = slo_episodes(
        request_samples,
        args.slo_window_seconds,
        args.slo_p95_ms,
        args.slo_min_requests,
        args.slo_stable_windows,
    )
    summary = {
        "method": {
            "latency_source": "k6 POST /ecomm/order http_req_duration samples only",
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
            phase_submitted, phase_completed, phase_dropped,
        ),
        "slo_violations": violations,
        "slo_windows": slo_windows,
        "pod_startup": summarize_pods(pods, app_ready_metrics, min(phase_starts.values())),
        "scale_up": summarize_scaling(phase_starts, pods, hpas, deployments),
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
        "collector_non_json_log_lines_ignored": collector_log_lines_ignored,
        "source_counts": {
            "pods": len(pods),
            "hpas": len(hpas),
            "deployments": len(deployments),
            "spring_application_ready_timestamps": len(app_ready_metrics),
        },
    }
    output = Path(args.output)
    output.write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
