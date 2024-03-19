#!/usr/bin/env bash
set -euo pipefail

# This script checks if the current user is allowed to change to GitHub Actions workflows. The script should be
# installed on self-hosted runners and defined in the `ACTIONS_RUNNER_HOOK_JOB_STARTED` environment variable.
# The script is triggered when a job has been assigned to a runner, but before the job starts running.
# https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/running-scripts-before-or-after-a-job#triggering-the-scripts

if [[ $GITHUB_REF != refs/pull* ]]; then
  echo "Not a pull request. Skipping"
  exit 0
fi

PR_NUMBER=$(echo "$GITHUB_REF" | awk -F / '{print $3}')
# To obtain sufficient quota for gh cli, it is recommended to set PAT in the `GH_TOKEN` environment variable.
# Check if there are any changes in .github/actions or .github/workflows
WORKFLOW_CHANGES=$(gh -R github.com/line/armeria pr diff "$PR_NUMBER" --name-only | grep -c '^.github/workflows\|^.github/actions' || true)
if [[ "$WORKFLOW_CHANGES" -eq "0" ]]; then
  echo "No changes in .github/actions or .github/workflows. Skipping."
  exit 0
fi

# dependabot[bot] is a special user that is used to update dependencies in the workflow files.
MAINTAINERS=("ikhoon" "dependabot[bot]" "jrhee17" "minwoox" "trustin")
for maintainer in "${MAINTAINERS[@]}"
do
  if [[ $maintainer == "$GITHUB_ACTOR" ]]; then
    echo "@$GITHUB_ACTOR is a maintainer. Allowed to change GitHub Actions workflows."
    exit 0
  fi
done

echo "@$GITHUB_ACTOR is not a maintainer. Disallowed to change GitHub Actions workflows."
exit 1
