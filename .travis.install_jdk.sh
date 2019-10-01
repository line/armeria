#!/bin/bash

set -eo pipefail

# For some reason, official Travis CI docs for using install-jdk.sh don't work so we use a different one.
# https://docs.travis-ci.com/user/languages/java/#switching-jdks-to-java-10-and-up-within-one-job
#
# https://github.com/sormuras/sormuras.github.io/blob/master/.travis.yml

wget https://github.com/sormuras/bach/raw/master/install-jdk.sh

OLD_JAVA_HOME="$JAVA_HOME"
source ./install-jdk.sh --feature 11

if [[ -n "$1" ]]; then
  # Can't source twice from install-jdk.sh in same build
  export JAVA_TEST_HOME="$HOME/openjdk13"
  bash ./install-jdk.sh --feature "$1" --target "$JAVA_TEST_HOME"
else
  export JAVA_TEST_HOME="$OLD_JAVA_HOME"
fi
