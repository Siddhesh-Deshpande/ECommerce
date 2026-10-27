#!/usr/bin/env python3
"""Check that ready application pods run the digests recorded by the last build."""

import json
import sys
from pathlib import Path


SERVICES = ("coordinator", "order", "inventory", "payment")


def read_properties(path):
    properties = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            properties[key] = value
    return properties


def expected_digests(value):
    try:
        repo_digests = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return set()
    if not isinstance(repo_digests, list):
        return set()
    return {entry.rsplit("@", 1)[-1] for entry in repo_digests if "@" in entry}


def image_digest(image_id):
    return image_id.rsplit("@", 1)[-1] if "@" in image_id else None


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_runtime_images.py BUILD_METADATA PODS_JSON")

    metadata = read_properties(sys.argv[1])
    pods = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    results = {}
    failed = False

    for service in SERVICES:
        expected = expected_digests(metadata.get(f"{service}_repo_digests", ""))
        observed = []
        for pod in pods.get("items", []):
            labels = pod.get("metadata", {}).get("labels", {})
            if labels.get("app") != service:
                continue
            for container in pod.get("status", {}).get("containerStatuses", []):
                if container.get("name") == service and container.get("imageID"):
                    observed.append({
                        "pod": pod.get("metadata", {}).get("name"),
                        "image_id": container["imageID"],
                        "ready": container.get("ready", False),
                    })

        observed_digests = {image_digest(item["image_id"]) for item in observed}
        if not expected:
            status = "unverifiable_no_pushed_digest"
            failed = True
        elif not observed:
            status = "missing_running_container"
            failed = True
        elif any(not item["ready"] for item in observed):
            status = "container_not_ready"
            failed = True
        elif not all(image_digest(item["image_id"]) in expected for item in observed):
            status = "digest_mismatch"
            failed = True
        else:
            status = "match"

        results[service] = {
            "status": status,
            "expected_digests": sorted(expected),
            "observed_containers": observed,
            "observed_digests": sorted(value for value in observed_digests if value),
        }

    print(json.dumps(results, indent=2, sort_keys=True))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
