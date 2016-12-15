#!/bin/bash -e
JDK_VERSION='jdk1.8.0_112'
JDK_DOWNLOAD_URL='http://download.oracle.com/otn-pub/java/jdk/8u112-b15/jdk-8u112-linux-x64.tar.gz'

JDK_HOME="$HOME/.jdk/$JDK_VERSION"
JDK_TARBALL="$HOME/.jdk/${JDK_VERSION}.tar.gz"

function install_symlink() {
  local DEFAULT_JDK_HOME="$HOME/.jdk/default"
  if [[ "$(readlink "$DEFAULT_JDK_HOME")" != "$JDK_HOME" ]]; then
    rm -fr "$DEFAULT_JDK_HOME"
    ln -sv "$JDK_HOME" "$DEFAULT_JDK_HOME"
  fi
  # Remove cyclic symlinks created by the buggy version of this script
  rm -vfr "$JDK_HOME/$JDK_VERSION" "$DEFAULT_JDK_HOME/$JDK_VERSION"
  "$DEFAULT_JDK_HOME/bin/java" -version
}

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  mkdir -p "$HOME/.jdk"
  wget --no-cookies --no-check-certificate \
       --header='Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie' \
       --output-document="$JDK_TARBALL" \
       "$JDK_DOWNLOAD_URL"

  rm -fr "$JDK_HOME"
  mkdir "$JDK_HOME"
  tar zxf "$JDK_TARBALL" -C "$HOME/.jdk"
  rm -f "$JDK_TARBALL"
fi

install_symlink

