#!/usr/bin/env bash
# Copied and adapted from:
# https://github.com/envoyproxy/java-control-plane/blob/2d4cd9cc450da78d7f32e0b1d0698dbeabc59539/tools/update-sha.sh

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

function find_sha() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 11 | grep -m 1 "version =" | awk '{ print $3 }' | tr -d '"' | tr -d ","
}

function find_date() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 11 | grep -m 1 "release_date =" | awk '{ print $3 }' | tr -d '"' | tr -d ","
}

function find_envoy_sha_from_tag() {
  local TAG=$1
  curl -s https://api.github.com/repos/envoyproxy/envoy/tags | grep "$TAG" -A 4 | grep sha | awk '{print $2}' | tr -d '"' | tr -d ","
}

CURRENT_ENVOY_RELEASE=$(cat envoy_release)
ENVOY_VERSION=$(find_envoy_sha_from_tag "$1")

CURL_OUTPUT=$(curl -s "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/api/bazel/repository_locations.bzl")

GOOGLEAPIS_SHA=$(find_sha "$CURL_OUTPUT" com_google_googleapis)
GOOGLEAPIS_DATE=$(find_date "$CURL_OUTPUT" com_google_googleapis)

PGV_GIT_SHA=$(find_sha "$CURL_OUTPUT" com_envoyproxy_protoc_gen_validate)
PGV_GIT_DATE=$(find_date "$CURL_OUTPUT" com_envoyproxy_protoc_gen_validate)

PROMETHEUS_SHA=$(find_sha "$CURL_OUTPUT" prometheus_metrics_model)
PROMETHEUS_DATE=$(find_date "$CURL_OUTPUT" prometheus_metrics_model)

XDS_SHA=$(find_sha "$CURL_OUTPUT" com_github_cncf_xds)
XDS_DATE=$(find_date "$CURL_OUTPUT" com_github_cncf_xds)

OPENTELEMETRY_SHA=$(find_sha "$CURL_OUTPUT" opentelemetry_proto)
OPENTELEMETRY_DATE=$(find_date "$CURL_OUTPUT" opentelemetry_proto)

CEL_SHA=$(find_sha "$CURL_OUTPUT" dev_cel)
CEL_DATE=$(find_date "$CURL_OUTPUT" dev_cel)

echo -n "# Update the versions here and run update-api.sh

# envoy (source: SHA from https://github.com/envoyproxy/envoy)
ENVOY_SHA=\"$ENVOY_VERSION\"

# dependencies (source: https://github.com/envoyproxy/envoy/blob/$ENVOY_VERSION/api/bazel/repository_locations.bzl)
GOOGLEAPIS_SHA=\"$GOOGLEAPIS_SHA\"  # $GOOGLEAPIS_DATE
PGV_VERSION=\"$PGV_GIT_SHA\"  # $PGV_GIT_DATE
PROMETHEUS_SHA=\"$PROMETHEUS_SHA\"  # $PROMETHEUS_DATE
OPENTELEMETRY_VERSION=\"$OPENTELEMETRY_SHA\"  # $OPENTELEMETRY_DATE
CEL_VERSION=\"$CEL_SHA\"  # $CEL_DATE
XDS_SHA=\"$XDS_SHA\"  # $XDS_DATE
"

# update tag in envoy_release file
echo $1 > envoy_release
