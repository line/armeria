#!/bin/bash

# For some reason, official Travis CI docs for using install-jdk.sh don't work so we use a different one.
# https://docs.travis-ci.com/user/languages/java/#switching-jdks-to-java-10-and-up-within-one-job
#
# https://github.com/sormuras/sormuras.github.io/blob/master/.travis.yml

wget https://github.com/sormuras/bach/raw/master/install-jdk.sh || exit $?

if [[ -n "$1" ]]; then
  # Can't source twice from install-jdk.sh in same build
  export JAVA_HOME="$HOME/openjdk13"
  bash ./install-jdk.sh --feature "$1" --target "$JAVA_HOME"
fi

export JAVA_TEST_HOME="$JAVA_HOME"
export JAVA_HOME="$HOME/openjdk11"
bash ./install-jdk.sh --feature 11 --target "$JAVA_HOME"
