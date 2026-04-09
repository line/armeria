# Release Notes Style Guide

Annotated examples from real Armeria release notes showing the exact formatting conventions.

---

## Major New Feature Entry

Bold title, description explaining value, `[Type](type)` links, code example with `👈👈👈`, PR refs.

```mdx
- **Standalone Athenz Token Validation**: You can now use [AthenzAuthorizer](type) to validate Athenz tokens
  outside of Armeria's request pipeline. This allows you to easily integrate Athenz authorization into
  third-party frameworks like Spring MVC or other servlet-based applications. #6607
  ```java
  ZtsBaseClient ztsBaseClient = ...;
  AthenzAuthorizer authorizer =
    AthenzAuthorizer.builder(ztsBaseClient)
                    .policyConfig(new AthenzPolicyConfig("your-domain"))
                    .build();

  // Validate tokens anywhere
  AccessCheckStatus status =
    authorizer.authorize(token, resource, action); // 👈👈👈
  ```
```

Key elements:
- `**Bold Title**:` prefix — only for significant features, not every entry
- Description explains *what you can now do* and *why it matters*
- `[AthenzAuthorizer](type)` — Armeria API type link
- Code block indented under the bullet (2 spaces)
- `// 👈👈👈` on the line the reader should focus on
- `#6607` issue/PR references at end of description line (list both the issue and the PR)

---

## Minor New Feature Entry

One-liner without code example, for smaller additions.

```mdx
- **Per-Request Client TLS Configuration**: You can now specify [ClientTlsSpec](type) for each request using
  [RequestOptions#clientTlsSpec()](type) or [ClientRequestContext#clientTlsSpec()](type). #6551
```

Or even simpler:

```mdx
- [ConnectionPoolListener](type) now supports ping-related events. #6539 #6552
```

---

## Improvement Entry

Concise description with type links. No code example unless it changes user-facing API.

```mdx
- [XdsBootstrap](type) now supports SDS. #6597 #6610 #6654
- Improved warning logs by adding diagnostic context to frequent HTTP/2 connection errors. #6638
```

---

## Bug Fix Entry

Describe the symptom that was fixed, not the internal root cause.

```mdx
- [GrpcMeterIdPrefixFunction](type) records `grpc-status` correctly for responses which fail with an exception. #6606 #6621
- `X-Forwarded-For` header values with leading or trailing whitespace around comma-separated addresses
  (e.g., `"192.168.1.1 , 10.0.0.1"`) are now trimmed and parsed correctly. #6615
- Setting `pingIntervalMillis` to a value greater than 33 seconds no longer throws an exception. #6648
  - Linux keepalive socket options (`SO_KEEPALIVE` `TCP_KEEPIDLE`, `TCP_KEEPINTVL`) are no longer set by default.
```

Key patterns:
- "[Thing] now [works correctly]" or "[Thing] no longer [does wrong thing]"
- Sub-bullets for related side effects or additional context
- Use backticks for config names, error types, and header names

---

## Breaking Change Entry

State what changed and what users must do to migrate.

```mdx
- A subclass of [AbstractEndpointSelector](type) must implement [AbstractEndpointSelector#doSelectNow(ClientRequestContext)](type)
  instead of [AbstractEndpointSelector#selectNow(ClientRequestContext)](type), which is now a final method. #6535
```

For non-trivial migrations, include before/after code.

---

## Documentation Entry

Brief description with links to new/updated docs.

```mdx
- New comprehensive documentation on understanding and handling timeouts: #6592
  - [Understanding timeout and cancellation origins](https://armeria.dev/docs/advanced/understanding-timeouts)
  - Handling timeouts for streaming:
    - [Client-side streaming](https://armeria.dev/docs/client/timeouts#handling-timeouts-for-streaming-responses)
    - [Server-side streaming](https://armeria.dev/docs/server/timeouts#handling-timeouts-for-streaming-requests)
```

---

## Dependencies Section

Format: `- LibraryName oldVersion → newVersion`, sorted alphabetically.

```mdx
- Athenz 1.12.31 → 1.12.33
- BlockHound 1.0.15 → 1.0.16
- Jackson 2.20.1 → 2.21.0
- Kotlin 2.2.21 → 2.3.0
- Logback 1.5.23 → 1.5.27
- Spring 6.2.14 → 6.2.15, 7.0.2 → 7.0.3
- Spring Boot 3.5.8 → 3.5.10, 4.0.1 → 4.0.2
```

Rules:
- Use `→` (unicode arrow), not `->` or `-->`
- Use `-` dash bullets, not `*`
- Use the library's common name, not Maven artifact IDs
- Group multi-version bumps: `- Spring 6.2.14 → 6.2.15, 7.0.2 → 7.0.3`
- Omit build-only dependencies (anything under the `- Build` section in the raw dependency PR)
- Sort alphabetically (A → Z)

---

## Thank You Section

```mdx
## 🙇 Thank you

<ThankYou usernames={[
  'contributor1',
  'contributor2',
  'contributor3'
]} />
```

Rules:
- Usernames sorted alphabetically
- Remove bot accounts: `dependabot[bot]`, `CLAassistant`
- Core maintainers from `.github/CODEOWNERS` must always be included
- One username per line, single-quoted, comma-separated

---

## What NOT to Include

- **`- N/A` entries**: Remove sections that have no content instead of writing `- N/A`
- **`🗑 Maybe ignore` section**: Must be fully triaged and removed
- **Build-only dependencies**: `- Build` sub-section from dependency PRs
- **Internal refactoring details**: Changes with no user-facing impact
- **Site/docs dependency bumps**: npm bumps for `site-new/` or `docs-client/` (e.g., webpack, lodash)

## Type Link Rules

Use `[ClassName](type)` ONLY for Armeria public API references:
- Classes/interfaces: `[GrpcServiceBuilder](type)`
- Methods: `[GrpcServiceBuilder#enableEnvoyHttp1Bridge(boolean)](type)` — always use the full
  `ClassName#methodName(ParamType)` form. Never use backtick-only style like
  `` `enableEnvoyHttp1Bridge(true)` `` for Armeria public API references in prose.
- Annotations: `[@Blocking](type)`

Do NOT use `[Name](type)` for:
- JDK types (`String`, `Duration`, `CompletableFuture`, `List`)
- Third-party types (`Mono`, `Flux`, `HttpServletRequest`)
- Internal/private Armeria classes
- Generic concepts (use plain text or backticks instead)
- Method calls inside code blocks — code blocks use plain Java syntax, not `(type)` links
