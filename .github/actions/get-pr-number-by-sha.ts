/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { Octokit } from 'octokit';
import * as core from '@actions/core';

main();

async function main(): Promise<void> {
  const octokit = new Octokit({auth: process.env.GITHUB_TOKEN});
  const [owner, repo] = process.env.GITHUB_REPOSITORY.split("/");

  const sha = process.env.COMMIT_SHA;
  console.log(`💻 Getting pull request number for ${sha} ...`)
  const {data: {check_suites}} = await octokit.rest.checks.listSuitesForRef({
    owner: owner,
    repo: repo,
    ref: sha,
  });

  let prNumber = 0;
  for (const item of check_suites) {
    if (item.pull_requests.length > 0) {
      prNumber = item.pull_requests[0].number;
      break;
    }
  }
  if (prNumber === 0) {
    // The build is not triggered by a pull request.
    console.log("❔ No pull request found.");
    return;
  } else {
    console.log(`✅ Pull request number: ${prNumber}`);
    core.setOutput("pr_number", prNumber);
  }
}
