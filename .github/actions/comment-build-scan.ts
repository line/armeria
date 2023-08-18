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

main();

async function main(): Promise<void> {
  const octokit = new Octokit({auth: process.env.GITHUB_TOKEN});
  const buildScans = process.env.BUILD_SCANS.split(",");
  const prNumber = parseInt(process.env.PR_NUMBER);
  if (isNaN(prNumber)) {
    console.log(`❔PR_NUMBER is not set. Skipping comment.`);
    return;
  }

  const [owner, repo] = process.env.GITHUB_REPOSITORY.split("/");

  console.log(`💻 Getting jobs for ${process.env.RUN_ID} ...`);
  const {data: {jobs}} = await octokit.rest.actions.listJobsForWorkflowRun({
    owner: owner,
    repo: repo,
    run_id: parseInt(process.env.RUN_ID),
  });

  let commentBody = `## 🔍 Build Scan® (commit: ${process.env.COMMIT_SHA})\n\n`;
  commentBody += `| Job name | Status | Build Scan® |\n`;
  commentBody += `| --- | --- | --- |\n`;
  for (const scan of buildScans) {
    if (scan.trim().length === 0) {
      continue;
    }
    // build scan string pattern: "<job-name> https://ge.armeria.dev/xxxxxx"
    const [jobName, scanUrl]= scan.split(" ");
    const job = jobs.find(job => job.name === jobName);
    if (job.conclusion === 'success') {
      commentBody += `| [${job.name}](${job.url}) | ✅ | ${scanUrl} |\n`;
    } else {
      commentBody += `| [${job.name}](${job.url}) | ❌ (${job.conclusion}) | ${scanUrl} |\n`;
    }
  }

  console.log(`💻 Getting comments for #${prNumber} ...`);
  const scanComment = await findScanComment(octokit, owner, repo, prNumber);
  if (scanComment) {
    // Update the previous comment
    console.log(`📝 Updating the previous comment: ${scanComment.html_url} ...`);
    await octokit.rest.issues.updateComment({
      owner,
      repo,
      comment_id: scanComment.id,
      body: commentBody
    });
  } else {
    // If no previous comment, create a new one
    console.log(`📝 Creating a new comment for #${prNumber} ...`);
    const { data: newComment } = await octokit.rest.issues.createComment({
      owner,
      repo,
      issue_number: prNumber,
      body: commentBody
    });
    console.log(`💬 A new comment has been created: ${newComment.html_url}`);
  }
}

async function findScanComment(octokit: Octokit, owner: string, repo: string, prNumber: number, page: number = 1): Promise<any> {
  const pageSize = 30;
  const comments = await octokit.rest.issues.listComments({
    owner,
    repo,
    issue_number: prNumber,
    page,
    per_page: pageSize,
  });

  const comment = comments.data.find(comment =>
    comment.user.login === "github-actions[bot]" && comment.body.includes('Build Scan®'));
  if (comment) {
    return comment;
  } else {
    if (comments.data.length === 0 || comments.data.length < pageSize) {
      return null;
    } else {
      return await findScanComment(octokit, owner, repo, prNumber, page + 1);
    }
  }
}
