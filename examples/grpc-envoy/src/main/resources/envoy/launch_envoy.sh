#!/usr/bin/env bash
set -Eeuo pipefail

SED_COMMAND=$1

CONFIG=$(cat $2)
CONFIG_DIR=$(mktemp -d)
CONFIG_FILE="$CONFIG_DIR/envoy.yaml"

echo "${CONFIG}" | sed -e "${SED_COMMAND}" > "${CONFIG_FILE}"


shift 2
/usr/local/bin/envoy --drain-time-s 1 -c "${CONFIG_FILE}" "$@"

rm -rf "${CONFIG_DIR}"
