#!/usr/bin/env bash

# Build the four deployable services with one of the alternative image strategies.
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STRATEGY="${1:-}"
REGISTRY="${IMAGE_REGISTRY:-docker.io/siddhesh1712}"
TAG="${IMAGE_TAG:-latest}"
PUSH=0
BUILD_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
STAGING_IMAGES=()
IMAGES=()
declare -A SERVICE_BUILD_DURATIONS=()
BUILD_METADATA="$ROOT_DIR/results/.last-image-build.txt"

usage() {
  cat <<'EOF'
Usage: scripts/build-images.sh <multistage|jib|graalvm> [--push]

Environment:
  IMAGE_REGISTRY  Image repository prefix (default: docker.io/siddhesh1712)
  IMAGE_TAG       Image tag (default: latest)
EOF
}

if [[ "$STRATEGY" == "-h" || "$STRATEGY" == "--help" ]]; then usage; exit 0; fi
if (( $# > 2 )); then usage >&2; exit 2; fi
if [[ "${2:-}" == "--push" ]]; then PUSH=1; elif [[ -n "${2:-}" ]]; then usage >&2; exit 2; fi
case "$STRATEGY" in multistage|jib|graalvm) ;; *) usage >&2; exit 2 ;; esac
[[ -n "$REGISTRY" ]] || { echo "IMAGE_REGISTRY must not be empty" >&2; exit 2; }
[[ "$TAG" =~ ^[[:alnum:]_][[:alnum:]_.-]{0,127}$ ]] || {
  echo "IMAGE_TAG must be a valid Docker tag without a registry path" >&2
  exit 2
}

command -v mvn >/dev/null || { echo "mvn is required" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
rm -f "$BUILD_METADATA"

declare -A MODULES=(
  [coordinator]=coordinator
  [order]=order-service
  [inventory]=inventory-service
  [payment]=payment-service
)

BUILD_STARTED_NS="$(date +%s%N)"
BUILD_SETUP_STARTED_NS="$BUILD_STARTED_NS"
BUILD_SETUP_DURATION_SECONDS=0
if [[ "$STRATEGY" == "jib" ]]; then
  MAVEN_JAVA_VERSION="$(mvn -version | awk '/^Java version:/ {print $3; exit}')"
  if [[ "$MAVEN_JAVA_VERSION" =~ ^([0-9]+) ]]; then
    MAVEN_JAVA_MAJOR="${BASH_REMATCH[1]}"
  else
    MAVEN_JAVA_MAJOR=0
  fi
  if (( 10#$MAVEN_JAVA_MAJOR < 21 )); then
    echo "Jib builds require Maven to run on JDK 21 or newer; detected '${MAVEN_JAVA_VERSION:-unknown}'." >&2
    exit 1
  fi
  # Install the shared events module and service artifacts before Jib runs each
  # service POM independently, so Maven can resolve the reactor dependency.
  mvn -B -f "$ROOT_DIR/pom.xml" -DskipTests clean install
  BUILD_SETUP_ENDED_NS="$(date +%s%N)"
  BUILD_SETUP_DURATION_SECONDS="$(awk -v start="$BUILD_SETUP_STARTED_NS" -v end="$BUILD_SETUP_ENDED_NS" 'BEGIN {printf "%.3f", (end-start)/1000000000}')"
fi

for service in coordinator order inventory payment; do
  module="${MODULES[$service]}"
  # Build under temporary tags so a failed fourth build cannot leave the
  # canonical latest set containing a mix of old and new service images.
  image="${REGISTRY}/${service}:build-${BUILD_ID}"
  SERVICE_BUILD_STARTED_NS="$(date +%s%N)"
  case "$STRATEGY" in
    multistage)
      docker build \
        --file "$ROOT_DIR/$module/Dockerfile.multistage" \
        --tag "$image" \
        "$ROOT_DIR"
      ;;
    jib)
      mvn -B -f "$ROOT_DIR/$module/pom.xml" \
        -DskipTests \
        -Djib.to.image="$image" \
        com.google.cloud.tools:jib-maven-plugin:3.5.2:dockerBuild
      ;;
    graalvm)
      docker build \
        --file "$ROOT_DIR/$module/Dockerfile.graalvm" \
        --tag "$image" \
        "$ROOT_DIR"
      ;;
  esac
  SERVICE_BUILD_ENDED_NS="$(date +%s%N)"
  SERVICE_BUILD_DURATIONS[$service]="$(awk -v start="$SERVICE_BUILD_STARTED_NS" -v end="$SERVICE_BUILD_ENDED_NS" 'BEGIN {printf "%.3f", (end-start)/1000000000}')"
  STAGING_IMAGES+=("$image")
  IMAGES+=("${REGISTRY}/${service}:${TAG}")
done

# Promote all locally built images only after all four builds have succeeded.
for index in "${!IMAGES[@]}"; do
  docker image tag "${STAGING_IMAGES[$index]}" "${IMAGES[$index]}"
done
BUILD_ENDED_NS="$(date +%s%N)"
BUILD_DURATION_SECONDS="$(awk -v start="$BUILD_STARTED_NS" -v end="$BUILD_ENDED_NS" 'BEGIN {printf "%.3f", (end-start)/1000000000}')"

# Do not publish a mixed strategy set if one of the four image builds fails.
if (( PUSH )); then
  for image in "${IMAGES[@]}"; do
    docker push "$image"
  done
fi

mkdir -p "$ROOT_DIR/results"
BUILD_METADATA_TEMP="$BUILD_METADATA.tmp.$$"
{
  printf 'strategy=%s\nimage_registry=%s\nimage_tag=%s\nbuild_id=%s\nbuilt_at_utc=%s\npushed=%s\n' \
    "$STRATEGY" "$REGISTRY" "$TAG" "$BUILD_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$PUSH"
  printf 'build_duration_seconds=%s\nbuild_setup_duration_seconds=%s\nbuild_duration_includes_push=false\n' \
    "$BUILD_DURATION_SECONDS" "$BUILD_SETUP_DURATION_SECONDS"
  for service in coordinator order inventory payment; do
    image="${REGISTRY}/${service}:${TAG}"
    image_inspect="$(docker image inspect --format '{{.Id}}|{{json .RepoDigests}}|{{.Size}}|{{len .RootFS.Layers}}' "$image")"
    IFS='|' read -r image_id repo_digests image_size_bytes layer_count <<<"$image_inspect"
    image_size_mb="$(awk -v bytes="$image_size_bytes" 'BEGIN {printf "%.3f", bytes/1000000}')"
    printf '%s_image=%s\n%s_image_id=%s\n%s_repo_digests=%s\n%s_image_size_bytes=%s\n%s_image_size_mb=%s\n%s_layer_count=%s\n%s_build_duration_seconds=%s\n' \
      "$service" "$image" "$service" "$image_id" "$service" "$repo_digests" \
      "$service" "$image_size_bytes" "$service" "$image_size_mb" "$service" "$layer_count" \
      "$service" "${SERVICE_BUILD_DURATIONS[$service]}"
  done
} >"$BUILD_METADATA_TEMP"
mv "$BUILD_METADATA_TEMP" "$BUILD_METADATA"

for image in "${STAGING_IMAGES[@]}"; do
  docker image rm "$image" >/dev/null
done

printf '\nImages built with %s strategy:\n' "$STRATEGY"
for service in coordinator order inventory payment; do
  printf '  %s/%s:%s\n' "$REGISTRY" "$service" "$TAG"
done
printf 'Build time (all four images, excluding push): %s seconds\n' "$BUILD_DURATION_SECONDS"
