#!/bin/bash

set -eo pipefail

# For some reason, official Travis CI docs for using install-jdk.sh don't work so we use a different one.
# https://docs.travis-ci.com/user/languages/java/#switching-jdks-to-java-10-and-up-within-one-job
#
# https://github.com/sormuras/sormuras.github.io/blob/master/.travis.yml

wget https://github.com/sormuras/bach/raw/master/install-jdk.sh

if [[ -n "$1" ]]; then
  FEATURE="$1"
  shift
  # Can't source twice from install-jdk.sh in same build so we go ahead and manually set up variables.
  export JAVA_TEST_HOME="$HOME/.jdk/openjdk$FEATURE"
  if [[ ! -d "$JAVA_TEST_HOME" ]]; then
    bash ./install-jdk.sh --feature "$FEATURE" --target "$JAVA_TEST_HOME"
    # install-jdk.sh downloads to this fixed filename, preventing from downloading again
    rm -f jdk.tar.gz
  fi
else
  export JAVA_TEST_HOME="$JAVA_HOME"
fi

NEW_JAVA_HOME="$HOME/.jdk/openjdk12"
if [[ ! -d "$NEW_JAVA_HOME" ]]; then
  bash ./install-jdk.sh --feature 12 --target "$NEW_JAVA_HOME"
  rm -f jdk.tar.gz
fi
export JAVA_HOME="$NEW_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
