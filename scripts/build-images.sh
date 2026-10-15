#!/usr/bin/env bash

# Build the four deployable services with one of the alternative image strategies.
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STRATEGY="${1:-}"
REGISTRY="${IMAGE_REGISTRY:-docker.io/siddhesh1712}"
TAG="${IMAGE_TAG:-latest}"
PUSH=0

usage() {
  cat <<'EOF'
Usage: scripts/build-images.sh <multistage|jib|graalvm> [--push]

Environment:
  IMAGE_REGISTRY  Image repository prefix (default: docker.io/siddhesh1712)
  IMAGE_TAG       Image tag (default: latest)
EOF
}

if [[ "$STRATEGY" == "-h" || "$STRATEGY" == "--help" ]]; then usage; exit 0; fi
if [[ "${2:-}" == "--push" ]]; then PUSH=1; elif [[ -n "${2:-}" ]]; then usage >&2; exit 2; fi
case "$STRATEGY" in multistage|jib|graalvm) ;; *) usage >&2; exit 2 ;; esac

command -v mvn >/dev/null || { echo "mvn is required" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

declare -A MODULES=(
  [coordinator]=coordinator
  [order]=order-service
  [inventory]=inventory-service
  [payment]=payment-service
)

if [[ "$STRATEGY" == "jib" ]]; then
  # Install the shared events module and service artifacts before Jib runs each
  # service POM independently, so Maven can resolve the reactor dependency.
  mvn -f "$ROOT_DIR/pom.xml" -DskipTests install
fi

for service in coordinator order inventory payment; do
  module="${MODULES[$service]}"
  # Reuse the deployment's canonical image names. Build/push and measure one
  # strategy at a time before replacing these tags with the next strategy.
  image="${REGISTRY}/${service}:${TAG}"
  case "$STRATEGY" in
    multistage)
      docker build \
        --file "$ROOT_DIR/$module/Dockerfile.multistage" \
        --tag "$image" \
        "$ROOT_DIR"
      ;;
    jib)
      mvn -f "$ROOT_DIR/$module/pom.xml" \
        -DskipTests \
        -Djib.to.image="$image" \
        com.google.cloud.tools:jib-maven-plugin:3.4.5:dockerBuild
      ;;
    graalvm)
      docker build \
        --file "$ROOT_DIR/$module/Dockerfile.graalvm" \
        --tag "$image" \
        "$ROOT_DIR"
      ;;
  esac
  if (( PUSH )); then docker push "$image"; fi
done

printf '\nImages built with %s strategy:\n' "$STRATEGY"
for service in coordinator order inventory payment; do
  printf '  %s/%s:%s\n' "$REGISTRY" "$service" "$TAG"
done
