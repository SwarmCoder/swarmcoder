#!/usr/bin/env bash
set -eo pipefail

echo "Building SwarmCoder Docker Images..."

# SC-Java Image
echo "Building sc-java..."
docker build -t swarmcoder/sc-java:latest -f sc-sandbox/images/sc-java/Dockerfile sc-sandbox/images/sc-java

# Placeholders for future images
# docker build -t swarmcoder/sc-web:latest -f sc-sandbox/images/sc-web/Dockerfile sc-sandbox/images/sc-web
# docker build -t swarmcoder/sc-rust:latest -f sc-sandbox/images/sc-rust/Dockerfile sc-sandbox/images/sc-rust
# docker build -t swarmcoder/sc-python:latest -f sc-sandbox/images/sc-python/Dockerfile sc-sandbox/images/sc-python

echo "Images built successfully."
