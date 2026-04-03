---
name: release-note
description: Generate and polish release notes for an Armeria version. Runs site-new/release-note.ts to collect PR data from a GitHub milestone, then rewrites the skeletal output into publication-ready MDX. Invoked as `/release-note <version>` (e.g., `/release-note 1.38.0`).
---

# Release Note Generator

Generates skeletal release notes from a GitHub milestone using `site-new/release-note.ts`,
then rewrites them into polished, publication-ready MDX with rich descriptions, code examples,
and proper formatting.

## Prerequisites

- The `gh` CLI must be authenticated with access to `line/armeria`. Verify with `gh auth status`.
- A `GITHUB_ACCESS_TOKEN` environment variable is recommended for higher GitHub API rate limits.
  If not set, the script falls back to anonymous access (lower rate limits).
- Node.js and npm must be available. The `site-new/` directory must have dependencies installed
  (`npm install` in `site-new/`).

## Invocation

```
/release-note <version>
```

Example: `/release-note 1.38.0`

---

## Phase 0: Generate Skeletal Release Notes

1. Verify the GitHub milestone exists for the given version by checking:
   ```
   gh api repos/line/armeria/milestones --jq '.[] | select(.title == "<version>") | .number'
   ```
2. Run the release note generation script:
   ```
   cd site-new && npm run release-note <version>
   ```
3. Verify the output file was created at `site-new/src/content/release-notes/<version>.mdx`.
4. If the script fails (e.g., milestone not found, network error), report the error and stop.

## Phase 1: Load Draft and Study Style

1. Read the generated draft file at `site-new/src/content/release-notes/<version>.mdx`.
2. Read 3-4 recent polished release notes (e.g., `1.36.0.mdx`, `1.37.0.mdx`) to calibrate tone and style.
3. Read the style guide at `references/style-guide.md` for formatting rules.
4. Extract all PR/issue references (`#NNNN`) from every line of the draft.

## Phase 2: Gather PR Context from GitHub

For each unique PR number found in the draft:

1. Fetch PR details:
   ```
   gh pr view <number> --repo line/armeria --json title,body,labels,files
   ```
2. Parse the PR body to extract **Motivation**, **Modifications**, and **Result** sections.
3. Fetch PR review comments — these often contain important design decisions, caveats, and
   scope limitations (e.g., "this only applies to unary calls") that are not in the PR description:
   ```
   gh api repos/line/armeria/pulls/<number>/comments --jq '.[].body'
   ```
4. Extract linked issue numbers from the body (`Closes #NNNN`, `Fixes #NNNN`, `Resolves #NNNN`).
5. For each linked issue, fetch its context **including comments**, which often contain use cases,
   edge cases, and design discussions that inform the release note description:
   ```
   gh issue view <number> --repo line/armeria --json title,body
   gh api repos/line/armeria/issues/<number>/comments --jq '.[].body'
   ```
6. For PRs in the "New features" section that introduce significant new API:
   - Read key changed source files from the PR's `files` list to understand method signatures.
   - Look for usage examples in the PR description's Result section first — prefer these over
     constructing examples from scratch.
   - Check review comments for scope limitations, caveats, or known constraints that should be
     mentioned in the release note (e.g., "only supports unary methods", "HTTP/2 only").

**Rate limiting**: If fetching many PRs, batch requests and pause briefly between them to avoid
GitHub API rate limits.

## Phase 3: Triage "Maybe Ignore" Section

The script puts PRs without a recognized label into `🗑 Maybe ignore`. For each entry:

1. Check the PR's labels and description.
2. **Drop** items that are purely internal (CI config, build scripts, test infrastructure,
   non-user-facing refactoring, site/docs dependency bumps).
3. **Relocate** user-facing items to the correct section based on their actual impact:
   - API additions → New features
   - Performance or usability improvements → Improvements
   - Bug fixes → Bug fixes
   - Breaking API changes → Breaking changes
4. Report triage decisions to the user so they can override if needed.

## Phase 4: Rewrite Each Section

Rewrite every entry following the formatting rules in `references/style-guide.md`.

### Ordering

1. **Lead with user interest**: Place the top 3 entries that users would care about most first.
   Prioritize broadly applicable features (core, gRPC, Kubernetes) over niche modules (Athenz, xDS).
   Consider the size of the user base affected and how common the use case is.
2. **Then group by module**: After the top 3, group remaining entries by module/area so that
   related changes appear together (e.g., Athenz entries adjacent, Kubernetes entries adjacent).
3. This applies to all sections (New features, Improvements, Bug fixes, etc.).

### Key principles:

### New Features (`🌟 New features`)

- All new feature entries get a bold title prefix: `- **Feature Title**: Description. #NNNN`
- **Keep descriptions concise** — most entries should fit within 3 lines of prose.
  Only high-impact features (e.g., a brand-new module or paradigm-shifting API) warrant
  longer descriptions.
- Include a Java code example (5-15 lines) whenever possible. It may be omitted if there is
  no clear usage pattern to show.
  - Mark the most important line with `// 👈👈👈`
  - Indent code blocks with 2 spaces under the bullet.

### Improvements (`📈 Improvements`)

- Concise description of what improved and why it matters.
- Code examples only if the improvement changes how users interact with an API.

### Bug Fixes (`🛠️ Bug fixes`)

- Describe the symptom that was fixed, not the internal cause.
- Format: "[What was broken] now [works correctly]. #NNNN"

### Breaking Changes (`☢️ Breaking changes`)

- State clearly what changed and what users must do to migrate.
- Include before/after code if the migration is non-trivial.

### Documentation (`📃 Documentation`)

- Brief description with links to the new/updated docs if available.

### Deprecations (`🏚️ Deprecations`)

- State what is deprecated and what to use instead.

### All Sections — Common Rules

- Use `[ClassName](type)` for Armeria API types (classes, interfaces, annotations, methods).
  - For classes/interfaces: `[GrpcServiceBuilder](type)`
  - For methods: `[GrpcServiceBuilder#enableEnvoyHttp1Bridge(boolean)](type)` — always include the
    class name, method name, and parameter types. Do NOT use backtick-only style like
    `` `enableEnvoyHttp1Bridge(true)` `` for Armeria public API references in prose.
  - Do NOT use this syntax for JDK types (`String`, `Duration`, `CompletableFuture`), third-party
    types, or types that are not part of Armeria's public API.
- PR/issue references go at the end of the entry: `#6640 #6607`
- Do NOT copy PR titles verbatim — they are often terse commit-style messages.
- Do NOT fabricate code examples. Derive them from PR descriptions or actual source code.
- Keep entries self-contained — a reader should understand the change without clicking the PR link.

## Phase 5: Clean Up Dependencies Section

The raw script includes the full dependency update PR body, which uses a structured commit message format.

1. **Strip build-only dependencies**: Remove the `- Build` section and all its sub-bullets
   (these are testImplementation, annotationProcessor, and other non-production deps).
2. **Format each entry**: `- LibraryName oldVersion → newVersion`
   - Use the library's common name (e.g., `Jackson`, `Netty`, `gRPC-Java`, not the Maven artifact ID).
   - Use `→` (unicode arrow), not `->`.
3. **Group multi-version bumps** on one line when a library has multiple version streams:
   `- Spring 6.2.14 → 6.2.15, 7.0.2 → 7.0.3`
4. **Sort alphabetically** (A → Z).

## Phase 6: Finalize

1. **Remove empty sections**: Delete any section whose only content is `- N/A`.
2. **Remove "Maybe ignore"**: The `🗑 Maybe ignore` section must not appear in the final output.
3. **Deduplicate and sort contributors**: Ensure `<ThankYou usernames={[...]} />` has
   alphabetically sorted, deduplicated usernames. Remove bot accounts (`dependabot[bot]`,
   `CLAassistant`).
4. **Ensure consistent bullet style**: Use `-` (dash) for all bullets, not `*`.
5. **Write the final file** to `site-new/src/content/release-notes/<version>.mdx`.
6. **Show a summary** to the user: list the sections, entry count per section, and any entries
   flagged as uncertain (where PR context was insufficient to write a confident description).

---

## Execution Checklist

- [ ] Phase 0 — Ran `npm run release-note <version>` and verified output file exists
- [ ] Phase 1 — Read draft, recent examples, and style guide
- [ ] Phase 2 — Fetched PR/issue context for all referenced PRs
- [ ] Phase 3 — Triaged all "Maybe ignore" entries
- [ ] Phase 4 — Rewrote all entries per style guide
- [ ] Phase 5 — Cleaned up dependencies section
- [ ] Phase 6 — Removed empty sections, finalized file, reported summary

## Common Mistakes to Avoid

- **Copying PR titles as-is**: PR titles like "Fix NPE in FooBar" are not user-friendly.
  Rewrite as "Fixed a `NullPointerException` in [FooBar](type) when ..."
- **Fabricating code examples**: If you cannot find a clear usage pattern from the PR description
  or source code, write a descriptive sentence instead of guessing at code.
- **Over-linking types**: Only use `[Name](type)` for Armeria's own public API types, not for
  JDK classes, third-party libraries, or internal classes.
- **Including build dependencies**: The dependency update PR body contains `- Build` sub-bullets
  for test/build-only deps. These must be stripped.
- **Leaving `- N/A` sections**: The polished output should only contain sections with actual content.
- **Using `*` bullets**: Standardize on `-` dashes for all bullet points.
- **Missing `👈👈👈` callouts**: Every code example for a new feature should highlight the key line.
