import { Octokit } from 'octokit';

// A GitHub access token that has the write permission for both Armeria and Central Dogma is required.
const octokit = new Octokit({ auth: process.env.GITHUB_ACCESS_TOKEN });

main();

/**
 * Perform post-release process.
 * - Close milestone.
 * - Write release notes.
 * - Create a pull request to Central Dogma for updating Armeria version.
 */
async function main(): Promise<void> {
  const tag: string = process.argv.slice(2).shift();
  const releaseVersion = tag.replace("armeria-", "");
  const owner = 'line';
  const repo = 'armeria';
  const milestoneId = await getMilestoneId(owner, repo, releaseVersion);

  // Close the milestone
  console.log(`🎯 Closing milestone #${milestoneId}...`);
  await octokit.rest.issues.updateMilestone({
    owner,
    repo,
    milestone_number: milestoneId,
    due_on: new Date().toISOString(),
    state: "closed"
  })
  console.log(`🎯 https://github.com/line/armeria/milestone/${milestoneId} has been closed.`)

  // Create a new release
  console.log(`📝 Creating release notes ...`);
  await octokit.rest.repos.createRelease({
    owner,
    repo,
    name: tag,
    tag_name: tag,
    body: `See [the release notes](https://armeria.dev/release-notes/${releaseVersion}/) for the complete change list.`

  });
  console.log(`📝 https://github.com/line/armeria/releases/tag/${tag} has been updated.`)

  if (!releaseVersion.endsWith(".0")) {
    // Trigger Central Dogma workflow to upgrade Armeria version
    console.log(`⛓️ Triggering 'update-armeria-version' workflow in Central Dogma repository...`);
    await octokit.rest.actions.createWorkflowDispatch({
      owner: owner,
      repo: 'centraldogma',
      workflow_id: 'update-armeria-version.yml',
      ref: 'main',
      inputs: {
        armeria_version: releaseVersion
      },
    })
    console.log("⛓️ https://github.com/line/centraldogma/actions/workflows/update-armeria-version.yml has been triggered.")
  }
}

/**
 * Converts a version into a milestone number in GitHub.
 */
async function getMilestoneId(owner: string, repo: string, version: string): Promise<number> {
  const response = await octokit.request(
      'GET /repos/{owner}/{repo}/milestones',
      {
        owner,
        repo,
        direction: 'desc',
        per_page: 100,
        state: 'open',
      },
  );
  const found = response.data.find((milestone: any) => milestone.title === version);
  if (!found) {
    throw new Error(
        `Failed to find a milestone from the given version: ${version}.`,
    );
  }
  return found.number;
}
