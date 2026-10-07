# SwarmCoder worker sandbox image (spec §8): a JDK toolchain plus the in-container action
# server that the orchestrator drives over HTTP (/exec, /read, /write, /health) against the
# bind-mounted /workspace. Build context is this module directory; run `mvn package` first so
# target/action-server.jar exists.
#
#   docker build -t swarmcoder-worker:latest sc-sandbox-action-server
#
FROM eclipse-temurin:25-jdk

# Maven installed from the binary tarball (not apt) so it does not drag in a second default-jdk.
ARG MAVEN_VERSION=3.9.9
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends git ca-certificates curl; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz; \
    mkdir -p "${MAVEN_HOME}"; \
    tar -xzf /tmp/maven.tar.gz -C "${MAVEN_HOME}" --strip-components=1; \
    rm /tmp/maven.tar.gz; \
    git --version; mvn -version

# Non-root by default (spec §8.2). The container runs arbitrary model-authored commands, so
# uid 0 inside the container — which is uid 0 on the host kernel for anything that escapes a
# namespace — is not acceptable. uid/gid 1000 is deliberate and must stay stable: the bind-
# mounted /workspace is a host-owned git worktree, and on a Linux host that worktree belongs
# to the operator's own (typically 1000) account. DockerSandboxManager pins --user 1000:1000
# to match, so changing the number here silently breaks writes into /workspace.
ARG SANDBOX_UID=1000
ARG SANDBOX_GID=1000
# The Ubuntu base already ships an `ubuntu` account at 1000:1000, so evict whoever holds the
# ids before claiming them — the numbers are what matters (see above), not the names.
RUN set -eux; \
    if existing="$(getent passwd "${SANDBOX_UID}" | cut -d: -f1)" && [ -n "${existing}" ]; then \
        userdel -r "${existing}" || userdel "${existing}"; \
    fi; \
    if existing="$(getent group "${SANDBOX_GID}" | cut -d: -f1)" && [ -n "${existing}" ]; then \
        groupdel "${existing}"; \
    fi; \
    groupadd -g "${SANDBOX_GID}" sandbox; \
    useradd -u "${SANDBOX_UID}" -g "${SANDBOX_GID}" -m -d /home/sandbox -s /bin/bash sandbox; \
    mkdir -p /workspace /opt/m2-ro /opt/m2-cache; \
    chown "${SANDBOX_UID}:${SANDBOX_GID}" /workspace /home/sandbox

# HOME must be explicit: the rootfs is mounted read-only at runtime, and the JDK, git and
# Maven all write under $HOME. DockerSandboxManager mounts a tmpfs over /home/sandbox so
# those writes land in RAM and vanish with the container.
ENV HOME=/home/sandbox

# Maven local-repository split (see DockerSandboxManager.MAVEN_ARGS for the full rationale):
# the host's ~/.m2/repository is bind-mounted READ-ONLY at /opt/m2-ro/repository (the folder only,
# never the settings files beside it) and used as a resolver "tail"
# (read-only chained local repo, Maven 3.9+/resolver 1.9+), while the writable head lives on
# the /tmp tmpfs. Even a fully offline `mvn -o` build writes into its local repo
# (_remote.repositories, resolver-status.properties, lastUpdated markers), so a read-only
# local repo is not a workable configuration; a read-WRITE host mount is not acceptable either
# because the sandbox could then plant artifacts the operator's own builds later resolve.
# MAVEN_ARGS is honoured by the Maven 3.9 launcher script for every `mvn` invocation, so model-
# authored commands get this for free without having to remember the flags.
# /opt/m2-cache comes FIRST: it is a Docker named volume on the VM's own filesystem holding a
# copy of just the artifacts this project's builds resolve, mounted READ-ONLY. Reading the host's
# ~/.m2 across the Windows filesystem bridge costs 8-44 ms per file and was four fifths of every
# sandbox build (measured: 41-70 s in a sandbox against 10-12 s on the host, 11-13 s with this
# cache). /opt/m2-ro stays in the chain behind it and stays authoritative, so a cache miss is a
# few milliseconds and never a wrong artifact. See MavenRepoCache.
ENV MAVEN_ARGS="-Dmaven.repo.local=/tmp/m2 -Dmaven.repo.local.tail=/opt/m2-cache/repository,/opt/m2-ro/repository -Dmaven.repo.local.tail.ignoreAvailability=true"

COPY --chown=1000:1000 target/action-server.jar /opt/action-server.jar

USER 1000:1000
WORKDIR /workspace

# The action server listens on SC_ACTION_PORT, which DockerSandboxManager sets to 47871. It is
# deliberately NOT 8080: the container also holds the candidate's own application, and 8080 is the
# port a Java web application binds by default — when both wanted it the action server won, the
# application silently lost the bind, and anything looking at 8080 saw this server's 404 and read
# it as a broken candidate. DockerSandboxManager passes SC_ACTION_PORT=47871 for exactly that
# reason.
#
# Under `network: bridge` the orchestrator publishes this port to an ephemeral loopback host port.
# Under the default `network: none` nothing outside the container can reach it and the orchestrator
# drives the container over the Docker Engine exec API instead.
# The default stays 8080 ON PURPOSE, even though DockerSandboxManager always overrides it.
# A previously installed orchestrator jar probes 8080 for health, and it must keep working
# against a freshly built image: an image that moved itself would fail every launch from
# an older build with a health timeout that says nothing about the cause.
ENV SC_ACTION_PORT=8080
EXPOSE 8080 47871
CMD ["java", "-jar", "/opt/action-server.jar"]
