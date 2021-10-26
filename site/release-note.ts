import { Octokit } from 'octokit';
import { chain } from 'lodash';
import { JSDOM } from 'jsdom';
import fetch from 'node-fetch';
import fs from 'fs';

// A GitHub access token has more quotas than anonymous access.
// If not specified, defaults to anonymous.
const octokit = new Octokit({ auth: process.env.GITHUB_ACCESS_TOKEN });

enum Category {
  NewFeature = '🌟 New features',
  Improvement = '📈 Improvements',
  Bug = '🛠️ Bug fixes',
  Documentation = '📃 Documentation',
  Deprecation = '🏚️ Deprecations',
  BreakingChange = '☢️ Breaking changes',
  Dependency = '⛓ Dependencies',
  // Maintainers should manually decide whether to check in the PRs in this category.
  MaybeIgnore = '🗑 Maybe ignore',
}

interface PullRequest {
  category: Category;
  title: string;
  results: string[];
  references: number[];
  users: string[];
}

/**
 * Converts a version into a milestone number in GitHub.
 */
async function getMilestoneId(version: string): Promise<number> {
  const response = await octokit.request('GET /repos/{owner}/{repo}/milestones', {
    owner: 'line',
    repo: 'armeria',
    direction: 'desc',
    per_page: 100,
    state: 'all'
  });
  const milestone = response.data.find(milestone => milestone.title == version);
  if (!milestone) {
    throw new Error(`Failed to find a milestone from the given ${version}.`)
  }
  return milestone.number;
}

async function getAllPullRequests(milestone: number): Promise<PullRequest[]> {
  const response = await octokit.request('GET /repos/{owner}/{repo}/issues', {
    owner: 'line',
    repo: 'armeria',
    milestone: milestone.toString(),
    state: 'all'
  });

  const pullRequestIds: number[] = response.data
    .filter(it => it.html_url.includes('/pull/'))
    .map(it => {
      return it.number;
    });

  const result: PullRequest[] = [];
  for (const id of pullRequestIds) {
    console.log(`💻 Collecting information for https://github.com/line/armeria/pull/${id} ...`);

    const pr = await octokit.request('GET /repos/{owner}/{repo}/pulls/{pull_number}', {
      owner: 'line',
      repo: 'armeria',
      pull_number: id
    });

    const title: string = pr.data.title;
    let results: string[];
    if (title == 'Update dependencies') {
      // Use body as it is for the dependency PR.
      results = [pr.data.body.replace(/->/gi, '→')];
    } else {
      results = parseResult(pr.data.body);
    }
    const categories: Category[] = pr.data.labels.map(label => labelToCategory(label));

    const html = await fetch(pr.data.html_url);
    const dom: JSDOM = new JSDOM(await html.text());
    const issues: number[] = getLinkedIssues(dom);
    const participants: string[] = getParticipants(dom);
    const reporters: string[] = await Promise.all(issues.map((issue: number) => getIssueReporters(issue)));

    const elements: PullRequest[] = categories.map(category => {
      return {
        category,
        title,
        results,
        users: [pr.data.user.login, ...participants, ...reporters],
        references: [...issues, id]
      };
    });
    result.push(...elements);
  }
  return result;
}

/**
 * Summarize the description of a PR.
 */
function parseResult(body: string | null): string[] {
  if (!body) {
    return [];
  }
  const lines = body.split('\r\n');
  const value = chain(lines)
    .dropWhile(line => !line.includes('Result:'))
    .drop()
    .filter(line => !line.match(/([Cc]loses?|[Ff]ix(es)?) /))
    .value();

  const result: string[] = [];
  let wasDash = false;
  let inComment = false;
  for (const line of value) {
    if (line.length == 0) {
      continue;
    }

    if (line.startsWith('<!--')) {
      inComment = true;
      continue;
    }
    if (line.includes('-->')) {
      inComment = false;
      continue;
    }
    if (inComment) {
      continue;
    }

    if (line.startsWith('- ')) {
      result.push(line.replace(/^- /, ''));
      wasDash = true;
    } else {
      if (wasDash) {
        const last = result.pop();
        result.push(last + '\n' + line);
      } else {
        // A single result will be expected.
        if (result.length == 0) {
          result.push(line);
        } else {
          const last = result.pop();
          result.push(last + ' ' + line);
        }
      }
    }
  }
  return result;
}

/**
 * Extracts linked GitHub issues from the given PR page.
 * GitHub does not provide such an API at the moment.
 */
function getLinkedIssues(pullRequestDom: JSDOM): number[] {
  return Array.from(pullRequestDom.window.document.querySelectorAll('.css-truncate.my-1'))
    .map((el: any) => el.children[0].href)
    .map((href: string) => href.replace(/^.*\/issues\//, ''))
    .map((number: string) => parseInt(number));
}

/**
 * Extracts participants from the given PR page.
 * GitHub does not provide such an API at the moment.
 */
function getParticipants(pullRequestDom: JSDOM): string[] {
  return Array.from(pullRequestDom.window.document.querySelectorAll('a.participant-avatar'))
      .map((el: any) => el.href)
      .map((href: string) => href.replace(/^\//, ''));
}

async function getIssueReporters(issue: number): Promise<string> {
  const pr = await octokit.request('GET /repos/{owner}/{repo}/issues/{issue_number}', {
    owner: 'line',
    repo: 'armeria',
    issue_number: issue
  });
  return pr.data.user.login;
}

function renderReleaseNotes(pullRequests: PullRequest[]): string {
  const builder: string[] = [];
  builder.push('---', `date: ${today()}`, '---', '');

  for (const category of Object.values(Category)) {
    const changes: PullRequest[] = pullRequests.filter(pr => {
      return pr.category == category;
    });
    if (category == Category.Documentation && changes.length == 0) {
      continue;
    }

    builder.push(`## ${category}`, '');

    if (changes.length == 0) {
      builder.push('- N/A');
    } else {
      for (const change of changes) {
        if (change.category == Category.Dependency) {
          builder.push(change.results.shift());
        } else {
          const links = change.references.map(id => `#${id}`).join(' ');
          if (change.results.length == 0) {
            builder.push(`- ${change.title} ${links}`);
          } else {
            for (const result of change.results) {
              builder.push(`- ${result} ${links}`);
            }
          }
        }
      }
    }
    builder.push('');
  }

  builder.push('## 🙇 Thank you', '', '<ThankYou usernames={[');

  const users = chain(pullRequests)
    .flatMap(pr => pr.users)
    .sortBy()
    .uniq()
    .map(user => `  '${user}'`)
    .join(',\n')
    .value();
  builder.push(users, ']} />');
  return builder.join('\n');
}

function labelToCategory(label: any): Category {
  switch (label.name) {
    case 'defect': {
      return Category.Bug;
    }
    case 'dependencies': {
      return Category.Dependency;
    }
    case 'deprecation': {
      return Category.Deprecation;
    }
    case 'performance':
    case 'improvement': {
      return Category.Improvement;
    }
    case 'new feature': {
      return Category.NewFeature;
    }
    case 'documentation': {
      return Category.Documentation;
    }
    default:
      return Category.MaybeIgnore;
  }
}

function today(): string {
  const d = new Date();
  return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
}

async function main() {
  const version = process.argv.slice(2).shift();
  if (!version) {
    console.log('The release version is not specified.');
    console.log(`Usage: npm run release-note <version>`);
    process.exitCode = 1;
    return;
  }

  console.log(`🎬 Generating a skeletal release note for ${version} ...`);
  const milestoneId: number = await getMilestoneId(version);
  const pullRequests: PullRequest[] = await getAllPullRequests(milestoneId);
  const notes: string = renderReleaseNotes(pullRequests);

  const path = `${__dirname}/src/pages/release-notes/${version}.mdx`
  fs.writeFileSync(path, notes);
  console.log(`✅  The release note is successfully written to ${path}`)
}

main();
