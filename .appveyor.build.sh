#!/bin/bash
set -eo pipefail

JRE8_URL='https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u242-b08/OpenJDK8U-jre_x64_linux_hotspot_8u242b08.tar.gz'
JRE8_VERSION='AdoptOpenJDK-8u242b08'
JRE11_URL='https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.6%2B10/OpenJDK11U-jre_x64_linux_hotspot_11.0.6_10.tar.gz'
JRE11_VERSION='AdoptOpenJDK-11.0.6_10'
JDK14_URL='https://github.com/AdoptOpenJDK/openjdk14-binaries/releases/download/jdk-14%2B36/OpenJDK14U-jdk_x64_linux_hotspot_14_36.tar.gz'
JDK14_VERSION='AdoptOpenJDK-14_36'
BUILD_JDK_URL="$JDK14_URL"
BUILD_JDK_VERSION="$JDK14_VERSION"

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

# Prepare the environment variables based on the specified profile.
PROFILE="$1"
COVERAGE=0
case "$PROFILE" in
java8)
  TEST_JRE_URL="$JRE8_URL"
  TEST_JRE_VERSION="$JRE8_VERSION"
  TEST_JAVA_VERSION='8'
  ;;
java11)
  TEST_JRE_URL="$JRE11_URL"
  TEST_JRE_VERSION="$JRE11_VERSION"
  TEST_JAVA_VERSION='11'
  COVERAGE=1
  ;;
java14|site|leak)
  TEST_JRE_URL="$JDK14_URL"
  TEST_JRE_VERSION="$JDK14_VERSION"
  TEST_JAVA_VERSION='14'
  ;;
*)
  echo "Unknown profile: $PROFILE" >&2
  exit 1
  ;;
esac

export TEST_JAVA_VERSION
export JAVA_HOME="$HOME/jdk/build-$BUILD_JDK_VERSION"
export JAVA_TEST_HOME="$HOME/jdk/test-$TEST_JAVA_VERSION-$TEST_JRE_VERSION"
export PATH="$JAVA_HOME/bin:$PATH"

msg "HOME: $HOME"
msg "PWD: $PWD"

# Restore the home directory from the cache if necessary.
if [[ -d /var/cache/appveyor ]] && \
   [[ -n "$APPVEYOR_ACCOUNT_NAME" ]] && \
   [[ -n "$APPVEYOR_PROJECT_SLUG" ]] && \
   [[ -n "$APPVEYOR_REPO_BRANCH" ]]; then

  # Purge the cache directories not touched for last 7 days.
  msg "Purging the build cache directories not touched for last 7 days .."
  find /var/cache/appveyor -mindepth 4 -maxdepth 4 -type d -mtime +7 \
      -exec echo 'Purging:' {} ';' \
      -exec rm -fr {} ';'
  # Delete the empty directories.
  msg "Deleting the empty directories in the build cache .."
  find /var/cache/appveyor -mindepth 1 -maxdepth 4 -type d -empty \
      -exec echo 'Deleting:' {} ';' \
      -delete

  if [[ "$PURGE_CACHE" != '1' ]]; then
    # Restore the home directory from the cache.
    BRANCH_CACHE_DIR="/var/cache/appveyor/$APPVEYOR_ACCOUNT_NAME/$APPVEYOR_PROJECT_SLUG/branches/$APPVEYOR_REPO_BRANCH/$PROFILE"
    msg "Branch cache directory: $BRANCH_CACHE_DIR"
    if [[ -z "$APPVEYOR_PULL_REQUEST_NUMBER" ]]; then
      CACHE_DIR="$BRANCH_CACHE_DIR"
    else
      CACHE_DIR="/var/cache/appveyor/$APPVEYOR_ACCOUNT_NAME/$APPVEYOR_PROJECT_SLUG/pulls/$APPVEYOR_PULL_REQUEST_NUMBER/$PROFILE"
      msg "Pull request cache directory: $CACHE_DIR"
    fi

    # Fetch the home directory from the cache directory.
    if [[ -d "$CACHE_DIR" ]]; then
      touch "$CACHE_DIR/.."
      msg "Restoring $HOME from the build cache: $CACHE_DIR .."
      echo_and_run rsync -a --stats "$CACHE_DIR/" "$HOME"
    elif [[ -d "$BRANCH_CACHE_DIR" ]]; then
      touch "$BRANCH_CACHE_DIR/.."
      msg "Restoring $HOME from the branch build cache: $BRANCH_CACHE_DIR .."
      echo_and_run rsync -a --stats "$BRANCH_CACHE_DIR/" "$HOME"
    else
      msg "Cache does not exist."
    fi
  else
    # Purge the cache directory if 'PURGE_CACHE' is '1'.
    BRANCH_CACHE_DIR=''
    CACHE_DIR=''
    msg "Purging the build cache of the entire project .."
    echo_and_run rm -fr "/var/cache/appveyor/$APPVEYOR_ACCOUNT_NAME/$APPVEYOR_PROJECT_SLUG"
  fi
else
  BRANCH_CACHE_DIR=''
  CACHE_DIR=''
fi

# Download build JDK if necessary.
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  msg "Downloading the build JDK .."
  echo_and_run mkdir -p "$HOME/jdk/downloads"
  echo_and_run curl -L -o "$HOME/jdk/downloads/build.tgz" "$BUILD_JDK_URL"
  echo_and_run rm -fr "$JAVA_HOME" "$JAVA_HOME.tmp"
  echo_and_run mkdir -p "$JAVA_HOME.tmp"
  echo_and_run tar xf "$HOME/jdk/downloads/build.tgz" --strip-components=1 -C "$JAVA_HOME.tmp"
  echo_and_run mv "$JAVA_HOME.tmp" "$JAVA_HOME"
fi

# Download test JRE if necessary.
if [[ ! -x "$JAVA_TEST_HOME/bin/java" ]]; then
  msg "Downloading the test JRE .."
  echo_and_run mkdir -p "$HOME/jdk/downloads"
  echo_and_run curl -L -o "$HOME/jdk/downloads/test-$TEST_JAVA_VERSION.tgz" "$TEST_JRE_URL"
  echo_and_run rm -fr "$JAVA_TEST_HOME" "$JAVA_TEST_HOME.tmp"
  echo_and_run mkdir -p "$JAVA_TEST_HOME.tmp"
  echo_and_run tar xf "$HOME/jdk/downloads/test-$TEST_JAVA_VERSION.tgz" --strip-components=1 -C "$JAVA_TEST_HOME.tmp"
  echo_and_run mv "$JAVA_TEST_HOME.tmp" "$JAVA_TEST_HOME"
fi

# Print the version information.
msg "Version information:"
echo_and_run "$JAVA_HOME/bin/java" -version
echo_and_run "$JAVA_TEST_HOME/bin/java" -version
echo_and_run ./gradlew -version

# Create the symlinks for npm caches
msg "Setting up frontend caches .."
for FRONTEND_MODULE in docs-client site; do
  echo_and_run mkdir -p "$FRONTEND_MODULE/.gradle"
  for FRONTEND_CACHE in npm nodejs; do
    echo_and_run mkdir -p "$HOME/.gradle/caches/$FRONTEND_MODULE/$FRONTEND_CACHE"
    echo_and_run ln -sv "$HOME/.gradle/caches/$FRONTEND_MODULE/$FRONTEND_CACHE" \
      "$FRONTEND_MODULE/.gradle/$FRONTEND_CACHE"
  done
done

# Run the build.
if [[ "$COVERAGE" -eq 1 ]]; then
  GRADLE_CLI_OPTS="$GRADLE_CLI_OPTS -Pcoverage"
fi

msg "Building .."
case "$PROFILE" in
site)
  echo_and_run ./gradlew $GRADLE_CLI_OPTS --parallel --max-workers=4 :site:lint :site:site
  ;;
leak)
  echo_and_run ./gradlew $GRADLE_CLI_OPTS --parallel --max-workers=4 -Pleak -PnoLint test
  ;;
*)
  echo_and_run ./gradlew $GRADLE_CLI_OPTS --parallel --max-workers=4 lint build
  ;;
esac

if [[ "$COVERAGE" -eq 1 ]]; then
  # Send coverage reports to CodeCov.io.
  # Note: In Linux, AppVeyor sets 'true' to 'CI' and 'APPVEYOR',
  #       but CodeCov expects them to be 'True', so we set 'True' to them.
  export CI=True
  export APPVEYOR=True
  msg "Sending the test coverage report .."
  bash <(curl -s https://codecov.io/bash) || true
fi

# Update the cache directory.
if [[ -n "$CACHE_DIR" ]]; then
  msg "Updating the build cache: $CACHE_DIR .."
  echo_and_run mkdir -p "$(dirname "$CACHE_DIR")"
  if [[ ! -d "$CACHE_DIR" ]] && [[ -n "$BRANCH_CACHE_DIR" ]] && [[ -d "$BRANCH_CACHE_DIR" ]]; then
    # Create a hard link to make a differential copy and save disk space.
    echo_and_run cp -al "$BRANCH_CACHE_DIR" "$CACHE_DIR"
  fi
  echo_and_run rsync -a --stats --delete "$HOME/" "$CACHE_DIR" || true
  echo_and_run touch "$CACHE_DIR/.."
fi
