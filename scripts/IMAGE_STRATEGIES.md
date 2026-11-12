# Alternative application image builds

The existing service `Dockerfile`s remain the baseline. The alternatives are
available for coordinator, order, inventory, and payment:

- `Dockerfile.multistage` builds the executable JAR in a Maven/JDK stage and
  copies it into the existing Distroless Java runtime.
- Jib builds layered JVM images directly from each service's Maven project,
  using the same Distroless Java 21 nonroot runtime as the multistage strategy.
- `Dockerfile.graalvm` uses Spring AOT and GraalVM Native Image to build a
  native executable, then packages it in a small Debian runtime. The service
  POMs declare Native Build Tools. The Spring Boot parent `native` profile
  enables AOT processing; the service POM's `graalvm-build` profile binds the
  native compiler to Maven's `package` phase and targets
  `-march=compatibility`. This avoids requiring CPU instructions that are
  visible in the image builder but hidden from the Minikube node by VM CPU
  virtualization. The Order, Inventory, and Payment
  services pin Log4j 2.25.5 (which includes GraalVM reachability metadata), set
  their Log4j2 configuration explicitly, and include GraalVM resource metadata
  for the application configuration and Spring Boot's Log4j2 defaults. The
  application classes also register their Kafka event DTOs for Jackson binding
  so native images can serialize and deserialize outbox payloads. The shared
  outbox configuration round-trips every event DTO during application startup;
  a JSON compatibility failure therefore prevents a rollout from becoming
  ready instead of returning 500 for every order.

The build script cleans Maven outputs before building. Container builds exclude
`target`, `results`, logs, and IDE files from their context, and image builds use
temporary tags until all four service images build successfully. The deployment
tags remain `latest` for all strategies.

Build locally (Docker must be running; Jib also requires Maven to run on JDK 21+):

```bash
scripts/build-images.sh multistage
scripts/build-images.sh jib
scripts/build-images.sh graalvm
```

All three strategies use the same canonical image names so the Kubernetes
manifests do not need to change: `siddhesh1712/coordinator:latest`,
`siddhesh1712/order:latest`, `siddhesh1712/inventory:latest`, and
`siddhesh1712/payment:latest`. Build and measure one strategy at a time because
the next build replaces those tags. Push during the build by adding `--push`;
Docker must be logged in to the registry.

```bash
scripts/build-images.sh graalvm --push
```

After building (and pushing, if the cluster pulls from a registry), run the
regular experiment against the currently tagged images:

```bash
./run.sh
```

The `run.sh` deployment path uses `latest` by default. For a different
repository prefix, set the same prefix for building and deployment:

```bash
IMAGE_REGISTRY=registry.example.com/team IMAGE_TAG=latest \
  scripts/build-images.sh graalvm --push
IMAGE_REPOSITORY_PREFIX=registry.example.com/team IMAGE_TAG=latest \
  ./run.sh
```

The build script records total local build time (including Jib's Maven setup,
excluding registry push), per-service build times, Docker-reported image size,
root filesystem layer count, image IDs, and digests in a registry-and-tag keyed
file under `results/image-build-metadata/`. It also updates
`results/.last-image-build.txt` as a convenience pointer to the most recent
build. `run.sh` selects metadata by the requested `IMAGE_REPOSITORY_PREFIX` and
`IMAGE_TAG`, so building several tagged strategies before running one keeps the
correct strategy details and digest verification. The run copies the matching
metadata into its results directory and includes it under `image_build` in
`metrics-report.json`.

For example, build and push each strategy once, then choose the deployment tag
per experiment:

```bash
IMAGE_TAG=multistage scripts/build-images.sh multistage --push
IMAGE_TAG=jib scripts/build-images.sh jib --push
IMAGE_TAG=graalvm scripts/build-images.sh graalvm --push

IMAGE_TAG=graalvm ./run.sh
```

For a kubeadm/remote cluster, also set `METRICS_COLLECTOR_IMAGE` to a registry
name reachable by the cluster nodes when running `run.sh`.

For an independent wall-clock check, run this command (replace the strategy as
needed):

```bash
/usr/bin/time -p scripts/build-images.sh graalvm
```

The script's `build_duration_seconds` excludes `--push`; `/usr/bin/time`
includes the whole command, including push if `--push` is supplied. For study
results, repeat each strategy at least five times with the same build-cache
policy. Each build's registry-and-tag metadata file is retained separately, so
the per-strategy records are not overwritten by subsequent strategy builds.

Each experiment also captures Kubernetes pod JSON before and after the load.
The pod snapshots include the image IDs actually used by the cluster. The load
report counts HTTP response status codes by phase, which helps distinguish
application 4xx/5xx responses from transport failures.

GraalVM native compilation takes substantially more time and memory than the
JVM image builds. It also changes the runtime model, so record it as a separate
strategy in the experiment results. Build with the target cluster architecture
in mind; native executables are platform-specific.
