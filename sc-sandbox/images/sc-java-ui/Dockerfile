# SwarmCoder UI worker sandbox image: the ordinary worker image plus a real browser.
#
#   docker build -t swarmcoder-worker-ui:latest sc-sandbox/images/sc-java-ui
#
# WHY A DERIVED IMAGE AND NOT THE BASE ONE
# ----------------------------------------
# Chromium and its shared libraries are ~600 MB and most tasks never touch a screen. Putting them
# in swarmcoder-worker:latest would charge every candidate on every run for something only web
# tasks use. Building a wholly separate image instead would duplicate the JDK, git, Maven and the
# action server, and double the maintenance.
#
# This is the third option and it costs the least of the three: the image is
# `FROM swarmcoder-worker:latest`, so the JDK/Maven/action-server layers ARE the same layers —
# shared on disk, never pulled twice, never maintained twice. A machine that already has the
# worker image pays only for the browser layers, and only on runs that ask for this image.
#
# WHY THE BROWSER IS INSIDE THE CONTAINER
# ---------------------------------------
# Under the default `sandbox.network: none` Docker publishes no ports, so a browser on the
# workstation can never reach a server in the container. Loopback INSIDE the container is
# untouched by `--network none` — the container always has its own lo interface — so a browser in
# the same container reaches the application on 127.0.0.1 with no network at all. That is the
# entire reason this image exists.
#
# NOTHING HERE NEEDS THE NETWORK AT RUN TIME. Node and Chromium are downloaded once, at build
# time, into the image.
FROM swarmcoder-worker:latest

USER root

# Chromium's shared libraries. The list is written out rather than obtained from
# `playwright install --with-deps`, because that command carries a per-distro table and refuses on
# a release it does not know (this base is Ubuntu 26.04, newer than any table Playwright 1.44
# shipped). Naming the libraries keeps the image buildable on a base that moves ahead of
# Playwright. --no-install-recommends: recommends pull in a desktop's worth of packages.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libdbus-1-3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
        libdrm2 libxkbcommon0 libatspi2.0-0t64 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2t64 libx11-6 libxcb1 \
        libxext6 libexpat1 fonts-liberation xz-utils; \
    rm -rf /var/lib/apt/lists/*

# Node is Playwright's own runtime; the browser is driven by /opt/sc-browser/check.js.
# Installed from nodejs.org's tarball rather than apt, and the reason is size: Ubuntu's `npm`
# package unpacks its dependency tree as several hundred separate distro packages, and that apt
# layer measured 595 MB. The official tarball carries the same node plus npm in a third of it.
ARG NODE_VERSION=22.23.2
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "${arch}" in \
        amd64) nodearch=x64 ;; \
        arm64) nodearch=arm64 ;; \
        *) echo "unsupported architecture ${arch}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${nodearch}.tar.xz" \
        -o /tmp/node.tar.xz; \
    mkdir -p /opt/node; \
    tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1; \
    rm /tmp/node.tar.xz; \
    ln -s /opt/node/bin/node /usr/local/bin/node; \
    ln -s /opt/node/bin/npm /usr/local/bin/npm; \
    ln -s /opt/node/bin/npx /usr/local/bin/npx; \
    node --version

# Pinned to the same Playwright the Java client in sc-verify uses, so the browser build in the
# container is the browser build the harness is written against.
ARG PLAYWRIGHT_VERSION=1.44.0
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright

# The npm package and the Chromium download both happen HERE, with network. A container started
# from this image needs neither.
RUN set -eux; \
    npm install -g --no-audit --no-fund "playwright@${PLAYWRIGHT_VERSION}"; \
    npx --yes "playwright@${PLAYWRIGHT_VERSION}" install chromium; \
    chmod -R a+rX /opt/ms-playwright; \
    npm cache clean --force

# The in-container page checker. Reads a JSON spec on stdin, writes a JSON result on stdout, with
# screenshots base64-encoded inside it — so everything travels back over the Docker exec channel
# and nothing needs a port, a volume or a network.
COPY check.js /opt/sc-browser/check.js
RUN chmod a+r /opt/sc-browser/check.js

# NODE_PATH so the checker can require('playwright') from the global install whatever the cwd is.
ENV NODE_PATH=/opt/node/lib/node_modules
ENV SC_BROWSER_CHECK=/opt/sc-browser/check.js

USER 1000:1000
WORKDIR /workspace
