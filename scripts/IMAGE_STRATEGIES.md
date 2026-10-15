# Alternative application image builds

The existing service `Dockerfile`s remain the baseline. The alternatives are
available for coordinator, order, inventory, and payment:

- `Dockerfile.multistage` builds the executable JAR in a Maven/JDK stage and
  copies it into the existing Distroless Java runtime.
- Jib builds layered JVM images directly from each service's Maven project.
- `Dockerfile.graalvm` uses Spring AOT and GraalVM Native Image to build a
  native executable, then packages it in a small Debian runtime. The service
  POMs declare Native Build Tools. The Spring Boot parent `native` profile
  enables AOT processing; the service POM's `graalvm-build` profile binds the
  native compiler to Maven's `package` phase.

Build locally (Docker must be running):

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
repository, set the same prefix for building and deployment:

```bash
IMAGE_REGISTRY=registry.example.com/team IMAGE_TAG=latest \
  scripts/build-images.sh graalvm --push
IMAGE_REPOSITORY_PREFIX=registry.example.com/team IMAGE_TAG=latest \
  ./run.sh
```

GraalVM native compilation takes substantially more time and memory than the
JVM image builds. It also changes the runtime model, so record it as a separate
strategy in the experiment results. Build with the target cluster architecture
in mind; native executables are platform-specific.
