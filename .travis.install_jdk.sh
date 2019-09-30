#!/bin/bash

# For some reason, official Travis CI docs for using install-jdk.sh don't work so we use a different one.
# https://docs.travis-ci.com/user/languages/java/#switching-jdks-to-java-10-and-up-within-one-job
#
# https://github.com/sormuras/sormuras.github.io/blob/master/.travis.yml

export JAVA_TEST_HOME=$JAVA_HOME
wget https://github.com/sormuras/bach/raw/master/install-jdk.sh || exit $?
source ./install-jdk.sh --feature 11
