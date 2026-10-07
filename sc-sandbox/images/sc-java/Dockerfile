# NOT THE IMAGE THE PRODUCT USES. Nothing builds or runs this file.
#
# The worker sandbox image is built from `sc-sandbox-action-server/Dockerfile` (tag
# swarmcoder-worker:latest) and the browser-capable variant from `sc-sandbox/images/sc-java-ui`
# (tag swarmcoder-worker-ui:latest). This file is an early sketch that still says so only by
# implication - it has the action server commented out and an `ENTRYPOINT ["sleep","infinity"]` -
# and it has already sent one reader to the wrong Dockerfile when asking what the sandbox contains.
# Change one of the other two.

FROM eclipse-temurin:21-jdk-jammy

# Install Maven and Gradle
RUN apt-get update && apt-get install -y \
    maven \
    unzip \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Install Gradle
ENV GRADLE_VERSION=8.7
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
    && unzip gradle-${GRADLE_VERSION}-bin.zip -d /opt \
    && rm gradle-${GRADLE_VERSION}-bin.zip \
    && ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle

ENV PATH=$PATH:/opt/gradle/bin

# Add a non-root user (worker)
RUN useradd -ms /bin/bash worker

# Prepare the workspace directory
RUN mkdir /workspace && chown worker:worker /workspace
WORKDIR /workspace

# Copy the action server (To be built during maven packaging)
# COPY action-server.jar /opt/action-server.jar

# Run as non-root
USER worker

# Entrypoint for the action server
# ENTRYPOINT ["java", "-jar", "/opt/action-server.jar"]
ENTRYPOINT ["sleep", "infinity"]
