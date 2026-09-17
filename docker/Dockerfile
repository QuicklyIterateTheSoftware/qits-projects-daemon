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
# `qits-projects-daemon-protocol` is at this reactor's own CalVer, and while a release does now
# publish that jar, the publish happens in a LATER step of the release pipeline than this build, so
# a builder container with an empty ~/.m2 still has nowhere but the reactor to get it. Building it
# from source here is also what keeps the image and the jar the same bytes. Copying module by module
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

# Where the platform publishes the shared harness library (eu.wohlben.qits:qits-coding-agents and
# :qits-commands). NEW WITH THE HARNESS CUTOVER: this reactor built those two as modules until the
# union of both daemons' copies moved to its own repository, so this build resolved from Central
# alone and needed no registry at all. It does now, and a build that cannot reach it fails on an
# unresolved dependency rather than on anything that names the real cause — hence the ARG, with a
# default a developer's machine can resolve. `-s .qits-maven-settings.xml` is what makes an http://
# repository resolvable in the first place: Maven blocks external http repositories, and the
# settings' exact-repository-id mirror is the one thing that outranks the blocker. It reads this
# variable, so an unset one leaves the mirror url unexpanded.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. `.qits-maven-settings.xml` reads them as the
# `qits-central-proxy` server; Maven sends them only if the mirror route ever answers 401 (edge
# reads are anonymous today). The `RUN` reads the two mounted files itself: docker 26's
# BuildKit has no `env=` mount option. FILE form (`/run/secrets/<id>`, read into the command's own
# environment), not the `env=` mount form: env= needs a dockerfile frontend the builder chooses,
# while the bare file mount is served identically by every frontend version — and under the platform
# builder the frontend is the builder's, not this file's, to pick. Absent secrets leave both empty
# (`|| true` is every hand build's state), which the settings read exactly as they read unset.
#
# Tests are skipped because they are not skippable HERE: CommandRegistryTest spawns real PTYs and
# the socket suites bind real ports. `./mvnw verify` on a clone is the gate for those.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl projects-daemon -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# makeBom RIDES ON THE INVOCATION ABOVE rather than getting a second one: a second `./mvnw` would be
# a second layer on top of a native compile, and a re-resolve of the same reactor to produce a
# document the first run already had the model for. One RUN, one layer, one truth.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured in qits-artifacts-service on its first
# release: `projects-daemon` is <packaging>quarkus</packaging> and ships as this image (and, below,
# as a runnable jar in the `daemons` store) rather than as a Maven artifact, so it skips
# maven-deploy — still true now that the reactor publishes `qits-projects-daemon-protocol`, which is
# deployed by a separate step of `.config/qits/ci-event-release.yml` and not from in here at all.
# The plugin SKIPS deploy-skipping modules by default, with
# a green build and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. The
# spelling matters too: a bare -DskipNotDeployed=false is silently ignored, and only the
# cyclonedx.-prefixed property reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The daemon module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image.
# `.config/qits/ci-event-release.yml` reaches this one with `--opt target=sbom --output type=local`
# using the SAME secrets as the image build, so every layer above is a cache hit and the export
# costs seconds rather than a second native compile.
#
# THIS DOCUMENT IS THE DAEMON'S, NOT THE AGENT'S. Dockerfile.projects layers this binary onto the
# released toolchain, and what a consumer of THAT image needs to know is which two published images
# it is made of — so the release step writes the agent's document from the two references it passed,
# rather than from a Maven closure that would describe only half of it.
FROM scratch AS sbom
COPY --from=build /src/projects-daemon/target/sbom.json /sbom.json

# --- runnable ---------------------------------------------------------------------------------------
# The daemon as a RUNNABLE JAR, for qits-projects' pin test to download and execute.
#
# WHAT IT IS FOR. qits-projects pins the `qits/project-agent` image through this repository's
# released `qits-projects-daemon-protocol` jar (see ProjectAgentImage), and its pin test starts THIS
# daemon — at exactly the version that dependency names — against localhost and round-trips the real
# control-socket protocol before any agent container uses the pair. A container is out: CI step
# containers have no docker (`--cap-drop=ALL`, no privilege, no socket under `build: true`), and what
# the service talks to is the daemon inside the image, not the image.
#
# WHY A JAR AND NOT THE BARE NATIVE BINARY. The binary is compiled in the `build` stage above on
# `ubi9-quarkus-mandrel-builder-image` and runs, in the runtime stage below, on `ubi9/ubi-minimal` —
# so it is dynamically linked against **glibc**. Every one of this platform's CI step images is
# Alpine (`maven-base` is maven:3.9-eclipse-temurin-25-alpine, `ci-base` is docker:cli), so **musl**.
# A bare native binary would therefore be an artifact the consumer's gate could never execute: the
# test would `assumeTrue`-skip for ever, which is the one outcome worse than having no pin test at
# all. A jar runs on the JDK `maven-base` already carries.
#
# WHAT THE PIN TEST THEREFORE PROVES, AND WHAT IT DOES NOT. Same source, same reactor, same version,
# so the wire contract it exercises is exactly the one the image's daemon speaks — and the protocol
# is what a daemon bump breaks. It does NOT prove the native image's own linkage or its reflection
# registration; those stay covered where they belong, by this repository's own pipeline — this
# Dockerfile's native compile and the ITs that run against it — not by the consumer's.
#
# A SECOND `./mvnw`, AND THE ONLY OTHER ONE IN THIS FILE. It cannot ride the native RUN above — one
# Maven invocation produces one packaging — but it is a JVM package of a reactor already resolved
# into that layer's cache, so it costs seconds against a native compile's minutes. Same secrets, same
# settings file, same `-Dqits.maven.repository.url` as the native RUN, because it resolves the same
# released harness jars.
FROM build AS runnable
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl projects-daemon -am package -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.package.jar.enabled=true \
      -Dquarkus.package.jar.type=uber-jar

# The export stage proper: `FROM scratch` holding the one file, so `--output type=local` writes a
# directory with the jar in it and nothing else.
#
# THE FILENAME IS STABLE AND NOT `-runner`. `application.properties` sets
# `quarkus.package.output-name=qits-projects-daemon` AND `quarkus.package.jar.add-runner-suffix=false`
# — the same pair that gives the native binary its suffix-free name — so the uber-jar comes out as
# `target/qits-projects-daemon.jar`. If either of those lines is ever dropped, Quarkus writes
# `qits-projects-daemon-runner.jar` and this COPY fails loudly at build time, which is the right
# place for it: the consumer downloads this file by a name the release step hardcodes.
FROM scratch AS binary
COPY --from=runnable /src/projects-daemon/target/qits-projects-daemon.jar /qits-projects-daemon.jar

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
