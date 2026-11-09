# Startup metric definitions

This document defines the startup timestamps and durations written by the Kubernetes observer and analyzer. Keep it with exported result files when preparing plots or paper tables; metric names alone do not fully describe their meaning.

## Per-Pod timestamps and sources

| Ledger column | Source and meaning |
|---|---|
| `pod_created_at` | Pod `metadata.creationTimestamp` from the Kubernetes API. |
| `image_pull_started_at` | Earliest Pod Event with reason `Pulling`, using `event_time` when present and otherwise `first_timestamp` (then the API's other retained timestamp fields). |
| `image_pull_finished_at` | Earliest Pod Event with reason `Pulled`, using the same timestamp preference. |
| `container_created_at` | Earliest Pod Event with reason `Created`. |
| `container_started_at` | Earliest non-null `containerStatuses[].state.running.startedAt` found across the Pod's complete observed history. It is retained even if the Pod later terminates or is deleted. |
| `application_main_at` | `application.main.timestamp`, recorded in each service's `main()` method immediately before `SpringApplication.run`. The application exposes it as Unix epoch milliseconds; the observer reads it after the Pod becomes Ready. |
| `spring_ready_at` | `application.ready.timestamp`, recorded when Spring emits `ApplicationReadyEvent`; also Unix epoch milliseconds, read retrospectively from the ready Pod. |
| `kubernetes_ready_at` | The earliest recorded Pod `Ready` condition transition whose status is True. |

Lifecycle Events are joined to Pods by Pod UID. The observer's `observed_at` is when the collector received/wrote a record; it is not substituted for the Kubernetes event timestamp in phase calculations.

## Durations

For timestamp-based durations, the analyzer calculates `(end timestamp − start timestamp)` in seconds. A duration is left blank when either endpoint is missing or when the end precedes the start.

| Ledger / report metric | Calculation | Interpretation |
|---|---|---|
| `pod_creation_to_image_pull_start_seconds` | `image_pull_started_at − pod_created_at` | Scheduling and delay before the runtime reports a pull attempt. |
| `image_pull_seconds` | `image_pull_finished_at − image_pull_started_at` | Difference between Kubernetes Event timestamps. Event timestamps in this run are whole-second precision, so this value is coarse. Prefer the two message-derived columns below when comparing pull durations. |
| `image_pull_operation_seconds` | First duration in the Pod's earliest parseable `Pulled` message, e.g. `in 1.412s` | Runtime-reported image pull operation duration. It does not include the separate “including waiting” amount. |
| `image_pull_including_wait_seconds` | Parenthesized duration in that message, e.g. `(1m0.205s including waiting)` | Runtime-reported pull elapsed time including waiting/queueing. In the example this is 60.205 seconds; it is not evidence that 60 seconds of image bytes were downloaded. |
| `image_pull_complete_to_container_created_seconds` | `container_created_at − image_pull_finished_at` | Gap between Pulled and Created Event timestamps; coarse to Event timestamp precision. |
| `container_create_to_started_seconds` | `container_started_at − container_created_at` | Container creation to process start; uses Kubernetes Event and container status timestamps. |
| `container_start_to_application_main_seconds` | `application_main_at − container_started_at` | Approximation of runtime/JVM/native process startup up to the first application `main()` statement. |
| `application_main_to_spring_ready_seconds` | `spring_ready_at − application_main_at` | Spring Boot and application initialization after entering `main()`, including configured dependency initialization. |
| `spring_ready_to_kubernetes_ready_seconds` | `kubernetes_ready_at − spring_ready_at` | Delay from Spring's ready event until Kubernetes first reports the Pod Ready; includes probe cadence and readiness/dependency checks. |
| `pod_creation_to_kubernetes_ready_seconds` | `kubernetes_ready_at − pod_created_at` | End-to-end Pod creation-to-Ready time. This is the main lifecycle total. |
| Report `container_start_to_spring_application_ready` | `spring_ready_at − container_started_at` | Existing combined interval, equal to runtime-startup plus main-to-Spring-ready when all three timestamps exist. |

The new Event-message durations are parsed from the `Pulled` Event's text. The parser supports Go duration units (`h`, `m`, `s`, `ms`, `us`/`µs`, and `ns`). If a cluster/runtime uses a different message format, these columns remain blank; the raw `kubernetes-events.jsonl` is the audit source.

## Population and interpretation

- `pod-startup-phases.csv` has one row per Pod observed by the collector. It includes the four application labels (`coordinator`, `order`, `inventory`, `payment`) and other namespace Pods; filter to the application labels for the image-strategy study.
- The startup summary in `metrics-report.json` includes the four application labels and includes Pods created before k6 begins, such as initial deployment Pods, as well as later scale-up Pods.
- A row or summary sample is one Pod start, not one independent experimental repetition. Pods created in the same rollout share cluster conditions and are not independent trials.
- A “warm” image pull means the image layers are present in the node's runtime cache. `imagePullPolicy: Always` may still contact the registry, and a `Pulled` Event can report a short operation time or longer wait-inclusive time. Confirm node/cache condition and inspect both durations before interpreting this as transferred image data.
- The runtime-to-`main()` and main-to-Spring split describes where elapsed startup time falls; it does not establish causality by itself. Database/Kafka availability, resource contention, scheduling, and probe behavior can affect the intervals.
- Kubernetes Event and container `startedAt` timestamps are generally coarse compared with the application epoch-millisecond gauges. Do not infer sub-second accuracy for an interval whose endpoints come from whole-second sources.
- Historical runs can only be reanalyzed for fields present in their archived raw JSONL. Missing lifecycle Events or app metrics cannot be reconstructed after the fact.

## Example: pull time and wait time

Given a Kubernetes Event message:

```text
Successfully pulled image "example/app:tag" in 1.412s (1m0.205s including waiting).
```

the ledger records:

```text
image_pull_operation_seconds = 1.412
image_pull_including_wait_seconds = 60.205
```

These are two different reported durations. The second includes waiting/queueing and must not be described as 60.205 seconds of image download.
