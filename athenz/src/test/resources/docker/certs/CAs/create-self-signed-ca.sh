#!/usr/bin/env bash

set -eu
set -o pipefail

# to script directory
cd "$(dirname "$0")"
echo 'running...'

FILENAME='athenz_ca'
CN='Sample Self Signed Athenz CA' \
  openssl req -x509 -nodes \
  -newkey rsa:4096 -days 36500 \
  -config "${SELF_SIGN_CNF_PATH}" \
  -keyout "${DEV_ATHENZ_CA_KEY_PATH}" \
  -out "${DEV_ATHENZ_CA_PATH}" 2> /dev/null

FILENAME='user_ca'
CN='Sample Self Signed User CA' \
  openssl req -x509 -nodes \
  -newkey rsa:4096 -days 36500 \
  -config "${SELF_SIGN_CNF_PATH}" \
  -keyout "${DEV_USER_CA_KEY_PATH}" \
  -out "${DEV_USER_CA_PATH}" 2> /dev/null

FILENAME='service_ca'
CN='Sample Self Signed Service CA' \
  openssl req -x509 -nodes \
  -newkey rsa:4096 -days 36500 \
  -config "${SELF_SIGN_CNF_PATH}" \
  -keyout "${DEV_SERVICE_CA_KEY_PATH}" \
  -out "${DEV_SERVICE_CA_PATH}" 2> /dev/null

# convert pem cert to der format so that it can be imported into OS ( optional step )
openssl x509 -outform der -in "${DEV_ATHENZ_CA_PATH}" -out "${DEV_ATHENZ_CA_DER_PATH}"

# print result
cat <<EOF

self-signed CAs created.
  athenz_ca: ${DEV_ATHENZ_CA_PATH}
  key: ${DEV_ATHENZ_CA_KEY_PATH}

  user_ca: ${DEV_USER_CA_PATH}
  key: ${DEV_USER_CA_KEY_PATH}

  service_ca: ${DEV_SERVICE_CA_PATH}
  key: ${DEV_SERVICE_CA_KEY_PATH}

EOF
