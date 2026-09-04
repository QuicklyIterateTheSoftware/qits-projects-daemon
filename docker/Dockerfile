# qits-projects-daemon as a GraalVM-native container IMAGE: a Mandrel builder stage compiles the
# binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `./mvnw package
# -Dnative` produces `projects-daemon/target/qits-projects-daemon` with whatever GraalVM is on PATH.
# What this adds is the image around it — and a way for a machine with no GraalVM to get a binary at
# all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/projects-daemon:latest -f docker/Dockerfile .
#
# --- what this image is FOR, because it is not an agent container --------------------------------
# A project agent container is this binary running as PID 1 inside a toolchain image — debian plus
# node, maven, a JDK and a native toolchain, because the agent's whole job is to build and run the
# project's checkout. That toolchain is `qits/workspace-base`, published by `images/qits-oci-workspace`,
# so it is not this repo's to own and could not be built here: a clone of this repo alone builds and
# tests green with no monorepo and no docker, which is the rule that shape protects.
#
# So this image is the DISTRIBUTION of the binary, and a consumer layers it onto a toolchain — see
# Dockerfile.projects next to this file.
#
# Running this image directly is legitimate but idle: with no QITS_PROJECTS_DAEMON_URL it logs
# "no url configured — projects-daemon is idle" and stays up. There is no checkout here to serve.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds projects-daemon's dependencies from source —
# they are 1.0.0-SNAPSHOTs this repo never publishes (no distributionManagement, no <repositories>),
# so a builder container with an empty ~/.m2 has nowhere else to get them. Copying module by module
# would mean editing this file every time one is added, and forgetting to would fail the build with
# an unresolved dependency rather than anything that names the real cause. `.dockerignore` is what
# keeps this cheap — without it this line drags in every target/ dir.
COPY . .

# Where Maven Central is mirrored, when it is. CI passes the platform's pull-through
# (qits-platform-mirror's central proxy, the same authority the FROM line above pulls through);
# EMPTY — the default — means direct Maven Central. `.qits-maven-settings.xml` activates its
# central-proxy profile only on a non-empty value, so a hand build that passes nothing resolves
# exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. `.qits-maven-settings.xml` reads them as the
# `qits-central-proxy` server; Maven sends them only if the mirror route ever answers 401 (edge
# reads are anonymous today). Absent secrets leave both unset, which is every hand build's state.
#
# Tests are skipped because they are not skippable HERE: CommandRegistryTest spawns real PTYs and
# the socket suites bind real ports. `./mvnw verify` on a clone is the gate for those.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl projects-daemon -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# makeBom RIDES ON THE INVOCATION ABOVE rather than getting a second one: a second `./mvnw` would be
# a second layer on top of a native compile, and a re-resolve of the same reactor to produce a
# document the first run already had the model for. One RUN, one layer, one truth.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured in qits-artifacts-service on its first
# release: `projects-daemon` is <packaging>quarkus</packaging> and ships as this image rather than
# as a jar, so it skips maven-deploy — and the plugin SKIPS deploy-skipping modules by default, with
# a green build and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. The
# spelling matters too: a bare -DskipNotDeployed=false is silently ignored, and only the
# cyclonedx.-prefixed property reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The daemon module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image.
# `.config/qits/ci-event-release.yml` reaches this one with `--target sbom --output type=local`
# using the SAME secrets as the image build, so every layer above is a cache hit and the export
# costs seconds rather than a second native compile.
#
# THIS DOCUMENT IS THE DAEMON'S, NOT THE AGENT'S. Dockerfile.projects layers this binary onto the
# released toolchain, and what a consumer of THAT image needs to know is which two published images
# it is made of — so the release step writes the agent's document from the two references it passed,
# rather than from a Maven closure that would describe only half of it.
FROM scratch AS sbom
COPY --from=build /src/projects-daemon/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-projects-daemon` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, since this
# is PID 1 of a container a user is working inside.
COPY --from=build --chown=1001:root /src/projects-daemon/target/qits-projects-daemon /work/qits-projects-daemon
RUN chmod +x /work/qits-projects-daemon

# NOT exposed, and that is the point: ProjectsApi binds 127.0.0.1 and is reached over the reverse
# tunnel it dials out for. A peer container on the shared network gets a connection refused by the
# network stack rather than by a token check.
USER 1001

ENTRYPOINT ["/work/qits-projects-daemon"]
