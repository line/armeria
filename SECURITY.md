# Security Policy

## Reporting a Vulnerability

**Do not report security vulnerabilities through public issues, discussions, or pull requests.**

Report them privately via
[GitHub private vulnerability reporting](https://github.com/line/armeria/security/advisories/new).

Please include the affected version and modules, a minimal reproducer — a small `Server` or client
setup we can run is ideal — and the impact: what an attacker gains, and what privileges or position
they need to start with.

We are a small group of maintainers and cannot promise a fixed response time, but we take these
reports seriously and will get back to you as soon as we can. If you haven't heard anything for a
while, please feel free to ping us on the advisory thread.

We ask that you keep the report confidential and give us a reasonable chance to ship a fix before
disclosing publicly. If our pace becomes a problem for you, tell us — we would much rather agree on
a disclosure date together than have it come as a surprise.

Reporters are credited in the published advisory unless you ask otherwise. The Armeria project does
not operate a bug bounty program.

## Supported Versions

Security fixes go out in a new release of the latest version, and we do not backport them to older
minor lines — please keep up to date. We do occasionally backport ordinary bug fixes to an older
line when upgrading is hard (see [1.32.6](https://armeria.dev/release-notes/1.32.6/)), but that is a
case-by-case courtesy, not a security support commitment.

## Scope

In scope:

- Routing or path handling that lets a request bypass a decorator or reach a resource it should not.
- HTTP protocol handling: request smuggling, response splitting, header injection, or a
  disagreement between Armeria and a peer about where one message ends and the next begins.
- Certificate validation and TLS configuration flaws in the client or the server.
- Authentication and authorization flaws in `AuthService`, `armeria-saml`, `armeria-oauth2` or
  `armeria-athenz`.
- Credentials, tokens or other secrets leaking into logs, error responses or `DocService`.
- Remotely triggerable resource exhaustion that a server cannot configure its way out of.

The following are not, unless you can show otherwise:

- A flaw in your own handler rather than in Armeria — for example, concatenating user input into
  a file path or a redirect target yourself.
- Exhausting a server that has not configured the limits in
  [the production checklist](https://armeria.dev/docs/advanced/production-checklist/).
- Deliberately exposing `DocService`, metrics or other management endpoints to an untrusted network.
- Automated scanner output with no demonstrated impact.
- Dependency CVEs with no reachable call path from Armeria.

A vulnerability in Netty, gRPC or another dependency belongs to that project first. Tell us as well
if Armeria's use of it makes the impact worse, or if we can mitigate it on our side.
