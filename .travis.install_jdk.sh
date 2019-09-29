#!/bin/bash

# https://docs.travis-ci.com/user/languages/java/#switching-jdks-to-java-10-and-up-within-one-job
export JAVA_TEST_HOME=$JAVA_HOME
export JAVA_HOME=$HOME/openjdk11
$TRAVIS_BUILD_DIR/install-jdk.sh --install openjdk11 --target $JAVA_HOME
