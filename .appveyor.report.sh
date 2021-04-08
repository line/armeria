#!/bin/bash
set -eo pipefail

function msg() {
  echo -ne "\033[1;32m"
  echo -n "$@"
  echo -e "\033[0m"
}

function echo_and_run() {
  echo -ne "\033[36m"
  echo -n "$@"
  echo -e "\033[0m"
  "$@"
}

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <profile>" >&2
  exit 1
fi

TARBALL_BASENAME="reports-$1"
TARBALL="$TARBALL_BASENAME.tar"

msg 'Collecting the test reports ..'
echo_and_run rm -f "$TARBALL"
echo_and_run find . '(' -name 'hs_err_*.log' -or -path '*/build/reports/tests' ')' \
  -exec tar rf "$TARBALL" \
    --xform="s:./:$TARBALL_BASENAME/:" \
    --xform='s:/build/reports/tests::' \
    {} ';'

if [[ ! -f "$TARBALL" ]]; then
  msg "Found no test reports."
else
  msg 'Compressing the test reports ..'
  echo_and_run gzip "$TARBALL"

  msg 'Uploading the test reports ..'
  echo_and_run curl -F "file=@$TARBALL.gz" 'https://file.io/'
  echo
  msg 'Download the test reports from the URL above.'
fi
