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
fi

for service in coordinator order inventory payment; do
  module="${MODULES[$service]}"
  # Build under temporary tags so a failed fourth build cannot leave the
  # canonical latest set containing a mix of old and new service images.
  image="${REGISTRY}/${service}:build-${BUILD_ID}"
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
  STAGING_IMAGES+=("$image")
  IMAGES+=("${REGISTRY}/${service}:${TAG}")
done

# Promote all locally built images only after all four builds have succeeded.
for index in "${!IMAGES[@]}"; do
  docker image tag "${STAGING_IMAGES[$index]}" "${IMAGES[$index]}"
done

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
  for service in coordinator order inventory payment; do
    image="${REGISTRY}/${service}:${TAG}"
    image_id="$(docker image inspect --format '{{.Id}}' "$image")"
    repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "$image")"
    printf '%s_image=%s\n%s_image_id=%s\n%s_repo_digests=%s\n' \
      "$service" "$image" "$service" "$image_id" "$service" "$repo_digests"
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
