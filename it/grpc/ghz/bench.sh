#!/bin/bash

# Forked from https://github.com/LesnyRumcajs/grpc_bench/blob/27bb8ee0bb8bd6565e10693fe73fabd9aaaacca9/bench.sh

GRPC_SERVER_START=${GRPC_SERVER_START:-"true"}
GRPC_SERVER_PORT=${GRPC_SERVER_PORT:-"50051"}
GRPC_SERVER_STARTUP_TIME=${GRPC_SERVER_STARTUP_TIME:-"10"}
GRPC_SERVER_USE_BLOCKING_EXECUTOR=${GRPC_SERVER_USE_BLOCKING_EXECUTOR:-"false"}
GRPC_BENCHMARK_DURATION=${GRPC_BENCHMARK_DURATION:-"30s"}
GRPC_CLIENT_CONNECTIONS=${GRPC_CLIENT_CONNECTIONS:-"5"}
GRPC_CLIENT_CONCURRENCY=${GRPC_CLIENT_CONCURRENCY:-"50"}
GRPC_CLIENT_QPS=${GRPC_CLIENT_QPS:-"0"}
GRPC_CLIENT_QPS=$(( GRPC_CLIENT_QPS / GRPC_CLIENT_CONCURRENCY ))
GRPC_CLIENT_CPUS=${GRPC_CLIENT_CPUS:-"4"}
GRPC_REQUEST_PAYLOAD=${GRPC_REQUEST_PAYLOAD:-"100B"}

if ! command -v ghz > /dev/null 2>&1
then
    echo "ghz could not be found. Visit https://ghz.sh/docs/install for installation"
    exit
fi


stop_server() {
  if [ -n "${GRPC_SERVER_PID}" ]; then
    echo "==> ✋ Stopping gRPC server..."
    kill "${GRPC_SERVER_PID}" 2>/dev/null
    if [ "$1" != "done" ]; then
      exit 130
    fi
  fi
}

# Stop the running server with Ctrl+C
trap stop_server INT

if [ "${GRPC_SERVER_START}" = "true" ]; then
  echo "==> 🚀 Starting gRPC server..."
  BASEDIR=$(dirname $0)
  "${BASEDIR}"/../../../gradlew :it:grpc:ghz:run -PnoWeb -q &
  GRPC_SERVER_PID=$!

  sleep "${GRPC_SERVER_STARTUP_TIME}"
  # Waiting for the server to be fully started
  while true; do
     if curl -s -o /dev/null http://127.0.0.1:${GRPC_SERVER_PORT}; then
       break
     else
       sleep 2
     fi
  done
fi

echo "==> 🔥 Running gRPC benchmark using ghz..."
ghz --cpus "${GRPC_CLIENT_CPUS}" \
    --proto=src/main/proto/hello.proto \
    --call=helloworld.Greeter.SayHello \
    --insecure \
    --concurrency="${GRPC_CLIENT_CONCURRENCY}" \
    --connections="${GRPC_CLIENT_CONNECTIONS}" \
    --rps="${GRPC_CLIENT_QPS}" \
    --duration="${GRPC_BENCHMARK_DURATION}" \
    --data-file src/main/resources/"${GRPC_REQUEST_PAYLOAD}" \
    127.0.0.1:${GRPC_SERVER_PORT}

stop_server "done"
echo "==> ✅ All done."
