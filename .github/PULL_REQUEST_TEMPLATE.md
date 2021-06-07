Motivation:

Explain why you're making that change and what's the problem you're trying to solve.
These are examples:
- https://github.com/line/armeria/pull/3479#issue-621862219
- https://github.com/line/armeria/pull/3331#issue-567485208

Modifications:

- Describe the modifications you've done.

Result:

- Closes #<GitHub issue number>. (If this resolves the issue.)
- Describe the consequences that a user will face after this PR is merged.
  - For example:
    - You no longer see a `NullPointerException` when a request times out.
    - You can now monitor the state of all live threads and heap using `ManagementService`.

(We publish our [release notes](https://armeria.dev/release-notes/) using this result section of each PR.
So if this PR is something that we don't need to announce in the release notes, you don't have to go into detail
but simply write it such as "Less verbose".)
