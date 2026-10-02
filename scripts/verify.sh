#!/bin/sh
# Run the full backend verification inside a Maven container.
#
# Why a container: the host has Docker but no JDK/Maven, and the integration
# tests need Docker-in-Docker (Testcontainers -> real MySQL 8).
#
#   ./scripts/verify.sh          # unit tests only (fast, no Docker needed)
#   ./scripts/verify.sh --all    # unit + integration tests (needs Docker)
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODE=${1:-unit}

if [ "$MODE" = "--all" ]; then
  GOAL="verify"
  DOCKER_SOCK="-v /var/run/docker.sock:/var/run/docker.sock \
               -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"
else
  GOAL="test"
  DOCKER_SOCK=""
fi

# shellcheck disable=SC2086
docker run --rm \
  -v "$ROOT/backend:/app" \
  -w /app \
  -v m2cache:/root/.m2 \
  $DOCKER_SOCK \
  maven:3.9-eclipse-temurin-17 \
  mvn -B "$GOAL"