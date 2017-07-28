#!/bin/bash -e
JDK_VERSION='jdk-8u141'
JDK_DOWNLOAD_URL='http://download.oracle.com/otn-pub/java/jdk/8u141-b15/336fa29ff2bb4ef291e347e091f7f4a7/jdk-8u141-linux-x64.tar.gz'

JDK_HOME="$HOME/.jdk/$JDK_VERSION"
JDK_TARBALL="$HOME/.jdk/${JDK_VERSION}.tar.gz"

function install_symlink() {
  local DEFAULT_JDK_HOME="$HOME/.jdk/default"
  if [[ "$(readlink "$DEFAULT_JDK_HOME")" != "$JDK_HOME" ]]; then
    rm -fr "$DEFAULT_JDK_HOME"
    ln -sv "$JDK_HOME" "$DEFAULT_JDK_HOME"
  fi
  "$DEFAULT_JDK_HOME/bin/java" -version
}

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  mkdir -p "$HOME/.jdk"
  wget --no-cookies --no-check-certificate \
       --header='Cookie: oraclelicense=accept-securebackup-cookie' \
       --output-document="$JDK_TARBALL" \
       "$JDK_DOWNLOAD_URL"

  rm -vfr "$JDK_HOME"
  mkdir "$JDK_HOME"
  tar zxvf "$JDK_TARBALL" --strip 1 -C "$JDK_HOME"
  rm -vf "$JDK_TARBALL"
  # Remove the old versions
  rm -vfr "$HOME/.jdk"/jdk1.*
fi

install_symlink

