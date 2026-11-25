#!/usr/bin/env python3
"""Summarize run-local cold/warm application image pull events across RPS."""
import argparse
import csv
import glob
import json
import random
from collections import defaultdict
from pathlib import Path

APPS = {"coordinator", "order", "inventory", "payment"}
METRICS = ("image_pull_operation_seconds", "image_pull_including_wait_seconds")


def percentile(values, p):
    ordered = sorted(values)
    if not ordered:
        return None
    pos = (len(ordered) - 1) * p / 100
    lo, hi = int(pos), min(int(pos) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def read_config(path):
    fields = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep:
            fields[key] = value
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, nargs="?", default=Path("results"))
    parser.add_argument("--bootstrap", type=int, default=20000)
    parser.add_argument("--seed", type=int, default=20261120)
    args = parser.parse_args()
    output = []
    exclusion_file = Path(__file__).with_name("excluded_runs.txt")
    excluded = set()
    if exclusion_file.exists():
        for line in exclusion_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                excluded.add(line.split("\t", 1)[0])

    for strategy_dir in sorted(p for p in args.results.iterdir() if p.is_dir()):
        values = defaultdict(list)
        contributing_runs = defaultdict(set)
        rate_by_run = {}
        for ledger in sorted(strategy_dir.glob("*/pod-startup-phases.csv")):
            run_dir = ledger.parent
            if f"{strategy_dir.name}/{run_dir.name}" in excluded:
                continue
            config_path = run_dir / "load-test-config.txt"
            if not config_path.exists():
                continue
            config = read_config(config_path)
            rate_by_run[run_dir.name] = int(config.get("target_rps", "0"))
            grouped = defaultdict(list)
            with ledger.open(newline="", encoding="utf-8") as stream:
                for row in csv.DictReader(stream):
                    if row.get("app") not in APPS or not row.get("image_ids") or not row.get("image_pull_started_at"):
                        continue
                    grouped[(row["app"], row["image_ids"])].append(row)
            for rows in grouped.values():
                rows.sort(key=lambda row: (row["image_pull_started_at"], row.get("pod_uid", "")))
                for index, row in enumerate(rows):
                    state = "cold" if index == 0 else "warm"
                    for metric in METRICS:
                        try:
                            value = float(row[metric])
                        except (KeyError, TypeError, ValueError):
                            continue
                        key = (state, metric)
                        values[key].append(value)
                        contributing_runs[key].add(run_dir.name)

        for (state, metric), observations in sorted(values.items()):
            rng = random.Random(args.seed)
            bootstrap = [
                percentile(rng.choices(observations, k=len(observations)), 50)
                for _ in range(args.bootstrap)
            ]
            bootstrap.sort()
            runs = sorted(contributing_runs[(state, metric)])
            output.append({
                "strategy": strategy_dir.name,
                "cache_state": state,
                "metric": metric,
                "runs": len(runs),
                "pod_events": len(observations),
                "rps_by_run": {run: rate_by_run.get(run) for run in runs},
                "median_seconds": percentile(observations, 50),
                "bootstrap_95_ci_seconds": [percentile(bootstrap, 2.5), percentile(bootstrap, 97.5)],
                "observation_range_seconds": [min(observations), max(observations)],
                "bootstrap_resamples": args.bootstrap,
                "bootstrap_seed": args.seed,
            })
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
