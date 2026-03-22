---
name: eventloop-audit
description: Systematically audits any Armeria-based Java project for event loop blocking issues — discovers project patterns, scans for blocking operations, traces call chains, and produces a fix plan without modifying code. Use when experiencing latency spikes, connection timeouts, deadlocks, or thread starvation in Armeria services, or as a pre-release audit. Invoked as `/eventloop-audit` for the full project or `/eventloop-audit <path>` for a specific directory.
---

# Event Loop Blocking Audit for Armeria Projects

## Overview

A systematic, multi-phase audit that discovers how a project uses Armeria, scans for all categories of event loop blocking, traces call chains to determine thread context, and produces a structured report with fix plans. **No code is modified** — only analysis and planning.

This skill is **framework-generic** — it works on any Armeria-based Java project by discovering the project's patterns dynamically, not assuming specific class names or directory structures.

## When to Use

- After setting up a new Armeria-based project and want to verify thread safety
- Before a release to audit for latent event loop blocking issues
- When experiencing unexplained latency spikes, connection timeouts, or deadlocks in an Armeria server
- After integrating a new library that may contain blocking calls
- As a periodic health check on the codebase's non-blocking discipline

## Thread Model Reference

For a comprehensive guide on Armeria's thread pools, executor selection, and programmatic usage patterns, see [armeria-thread-pool-guide.md](references/armeria-thread-pool-guide.md).

**The Golden Rule**: If `Thread.currentThread() instanceof NonBlocking` — you **MUST NOT** block. Armeria's event loop threads implement the `NonBlocking` marker interface. Any blocking operation on these threads stalls ALL connections sharing that event loop, causing cascading latency and potential deadlocks.

---

## Phase 1: Discovery — Understand the Project's Armeria Usage

Before searching for problems, discover HOW the project uses Armeria. Launch an **Explore agent** (or do it yourself) with these specific searches. Record findings as they'll inform all subsequent phases.

### 1.1 Find Armeria Server Configuration

```
Grep: Server\.builder\(\)|ServerBuilder
Scope: **/*.java
```

Read each match and document:
- Worker group configuration (`workerGroup(n)`, `serviceWorkerGroup(n)`)
- Blocking executor configuration (`blockingTaskExecutor(...)`)
- Decorator chain (`.decorate(...)` calls)
- Request/idle timeout settings
- Any custom `EventLoopGroup` creation

### 1.2 Find HttpService Implementations

```
Grep: implements HttpService|extends AbstractHttpService|extends SimpleDecoratingHttpService
Scope: **/*.java
```

These classes have `serve()` methods that run on the event loop. List all of them — they are the primary audit targets.

### 1.3 Find Annotated Services

```
Grep: @Get\(|@Post\(|@Put\(|@Delete\(|@Head\(|@Patch\(|@Options\(
Scope: **/*.java
```

For each, check if the method or class has `@Blocking`. Methods without `@Blocking` run on the event loop.

### 1.4 Find gRPC Services

```
Grep: extends \w+ImplBase|GrpcService\.builder\(\)
Scope: **/*.java
```

Check whether `useBlockingTaskExecutor(true)` is configured in the `GrpcService.builder()`. If not, all gRPC handler methods run on the event loop.

### 1.5 Find Thrift Services

```
Grep: THttpService\.builder\(\)|THttpService\.of\(
Scope: **/*.java
```

Same check — look for `useBlockingTaskExecutor(true)`.

### 1.6 Find Decorators

```
Grep: SimpleDecoratingHttpService|DecoratingHttpServiceFunction|\.decorate\(
Scope: **/*.java
```

Every decorator's `serve()` method runs on the event loop. List them all.

### 1.7 Find Custom Executors and Thread Pools

```
Grep: blockingTaskExecutor\(\)|ctx\.eventLoop\(\)|Executors\.new|new ThreadPoolExecutor|new ForkJoinPool|ExecutorService
Scope: **/*.java
```

Document all custom thread pools and how they're used. These are the "safe zones" where blocking is OK.

### 1.8 Find Offloading Patterns

```
Grep: supplyAsync|thenApplyAsync|thenComposeAsync|thenAcceptAsync|ctx\.blockingTaskExecutor|makeContextAware
Scope: **/*.java
```

Understand the project's conventions for offloading work from the event loop. Look for:
- **Base class methods** that submit work to a blocking executor (e.g., a `runBlocking(Supplier)` method in a base handler)
- **Utility classes** that wrap `ctx.blockingTaskExecutor()` with additional logic (cancellation, metrics, tracing)
- **Project-specific annotations** that trigger blocking executor dispatch (beyond Armeria's `@Blocking`)

A wrapper is "safe" if it provably submits the lambda/callable to a non-event-loop executor before the lambda body executes. Read the wrapper's implementation to verify — don't trust method names alone.

### 1.9 Build Architecture Map

Synthesize findings into a concise map:
- **Event loop entry points**: List of classes/methods where the event loop calls into application code
- **Safe execution wrappers**: Project-specific methods that offload to blocking executors
- **Thread pool inventory**: All thread pools, their sizing, and purpose
- **Decorator chain**: The order in which decorators wrap services

---

## Phase 2: Systematic Detection — 7 Categories

Search the entire project (or scoped path) for each category of blocking pattern. Exclude test files (`**/test/**`, `**/tests/**`) and generated code (`**/generated/**`, `**/target/**`, `**/build/**`).

See [detection-patterns.md](references/detection-patterns.md) for the complete search patterns and triage guidance for all 7 categories:

1. **Direct Blocking Calls** — `.join()`, `.get()`, `Thread.sleep()`, `.await()`, `.lock()`, etc.
2. **Blocking I/O Operations** — File I/O, network, JDBC, serialization, encryption
3. **Synchronization** — `synchronized`, `ReentrantLock`, `StampedLock` on request paths
4. **CompletableFuture Continuations on Wrong Executor** — `.thenApply()` without `Async` where completing thread is event loop
5. **Thread Pool Misconfiguration** — Unbounded pools, missing shutdown, undersized blocking executor
6. **Missing Armeria Idioms** — Missing `@Blocking`, missing `useBlockingTaskExecutor(true)`, blocking cancel handlers, missing context propagation
7. **Transitive / Hidden Blocking** — DNS resolution, class loading, synchronous logging appenders

---

## Phase 3: Call Chain Analysis

For each finding from Phase 2, perform call chain analysis to determine the thread context.

### Analysis Steps

1. **Identify enclosing method** — What method contains the potentially blocking call?

2. **Find all callers** — Grep for the method name across the project:
   ```
   Grep: methodName\(
   Scope: **/*.java
   ```
   When handling overloaded methods (same name, different signatures), read each call site to confirm it calls the blocking variant. Also check superclass/interface hierarchies — a `serve()` override in a subclass is still called from the event loop if the parent is an `HttpService`.

3. **Trace to entry point** — Follow the call chain upward until you reach one of:
   - An Armeria entry point (`serve()`, `@Get`/`@Post` handler, gRPC handler, decorator)
   - A thread pool submission (`blockingTaskExecutor().execute(...)`, `supplyAsync(...)`, etc.)
   - A framework callback (`whenRequestCancelling`, `setOnCancelHandler`)
   - A lambda/method reference passed to an executor (`.submit(this::methodName)`)

   **Handling ambiguity**: If a method is called from BOTH event loop paths AND thread pool paths, classify based on the worst case (event loop path). Note both paths in the finding.

4. **Determine thread context** — At the entry point:
   - Is there a `@Blocking` annotation? → Safe
   - Is there `useBlockingTaskExecutor(true)`? → Safe
   - Is it inside a `supplyAsync(..., blockingExecutor)` call? → Safe
   - Is it inside a project-specific safe wrapper (discovered in Phase 1.8)? → Safe
   - None of the above? → **Event loop**

5. **Classify severity using this decision tree:**

```
Is the blocking call provably inside a blocking executor or thread pool?
├── YES → SAFE (exclude from report or list separately)
├── NO, it's provably on the event loop:
│   ├── Is it an I/O operation, .join(), .get() without timeout, or Thread.sleep()?
│   │   └── YES → CRITICAL
│   ├── Is it .get(timeout, unit) or .await(timeout, unit)?
│   │   └── YES → HIGH (blocks event loop for the timeout duration)
│   ├── Is it a synchronized block or lock?
│   │   ├── Can it contend with a thread that holds the lock for >1ms?
│   │   │   └── YES → HIGH
│   │   └── Low contention, very fast critical section?
│   │       └── LOW
│   └── Is it a very fast operation (System.getProperty, in-memory map lookup)?
│       └── LOW
└── UNDETERMINED (e.g., CF continuation where completing thread varies):
    ├── Could the continuation body block?
    │   ├── YES → MEDIUM (must verify at runtime or refactor to be safe)
    │   └── NO (continuation is pure computation) → LOW
    └── Is the source future always completed by a thread pool?
        └── YES → SAFE
```

**Key distinction**: MEDIUM means "we cannot statically prove the thread context" — it requires either runtime verification (add logging/assertion) or defensive refactoring (use `*Async` variant with explicit executor).

---

## Phase 4: Fix Plan Templates

For each non-SAFE finding, recommend one of the fix approaches documented in [fix-templates.md](references/fix-templates.md):

| Template | When to use |
|----------|-------------|
| **FP-1** | Replace `.join()`/`.get()` with async chaining |
| **FP-2** | Offload blocking work to `ctx.blockingTaskExecutor()` |
| **FP-3** | Add `@Blocking` annotation to annotated service methods |
| **FP-4** | Enable `useBlockingTaskExecutor(true)` for gRPC/Thrift services |
| **FP-5** | Use `*Async` CF variant with explicit executor |
| **FP-6** | Add timeout to unavoidable blocking |
| **FP-7** | Make thread pool initialization thread-safe |
| **FP-8** | Wire context propagation with `makeContextAware` |

**Document the recommendation — do not apply it.**

---

## Phase 5: Produce the Report

### Per-Finding Format

```markdown
## Finding [ID]: [Short descriptive title]

**Severity**: CRITICAL / HIGH / MEDIUM / LOW
**File**: [relative path]:[line number]
**Thread context**: Event loop / Blocking executor / Undetermined
**Detection category**: [1-7 with category name]

### Description
[What the code does and why it's a blocking risk on the event loop]

### Call chain
[Armeria entry point (event loop)] → [Method A] → [Method B] → [blocking call at line X]

### Recommended fix
[Reference FP-1 through FP-8, with specific application to this case:
 which method to change, what the new code should look like, which executor to use]

### Risk assessment
[What could break when applying the fix, edge cases to consider, tests to run]
```

### Summary Table

At the end of all findings, produce a summary:

```markdown
| # | Severity | File | Line | Category | Blocking Pattern | Fix |
|---|----------|------|------|----------|-----------------|-----|
| 1 | CRITICAL | FooService.java | 42 | 1 - Direct blocking | `.join()` | FP-1 |
| 2 | HIGH | BarHandler.java | 88 | 2 - I/O | FileInputStream | FP-2 |
| ... | ... | ... | ... | ... | ... | ... |
```

### Statistics

```markdown
- Total findings: [N]
- CRITICAL: [N] | HIGH: [N] | MEDIUM: [N] | LOW: [N] | SAFE (excluded): [N]
- By category: Cat.1: [N] | Cat.2: [N] | Cat.3: [N] | Cat.4: [N] | Cat.5: [N] | Cat.6: [N] | Cat.7: [N]
```

### Follow-Up Recommendations

Always include these recommendations regardless of findings:

1. **Runtime detection**: Consider adding [BlockHound](https://github.com/reactor/BlockHound) to the test suite — it detects blocking calls on non-blocking threads at runtime
2. **Async logging**: Verify that the logging framework uses async appenders in production to avoid blocking on `log.*()` calls
3. **Event loop monitoring**: Enable Armeria's built-in event loop blocking detection:
   ```
   -Dcom.linecorp.armeria.reportBlockedEventLoop=true
   ```
4. **Code review checklist**: Add "Does this handler offload blocking work?" to the team's code review checklist for Armeria services

---

## Execution Checklist

Follow this checklist to ensure nothing is skipped. Check off each item as you complete it.

### Phase 1: Discovery
- [ ] 1.1 — Found and documented Armeria server configuration(s)
- [ ] 1.2 — Listed all `HttpService` implementations
- [ ] 1.3 — Listed all annotated services, noted `@Blocking` presence/absence
- [ ] 1.4 — Listed all gRPC services, noted `useBlockingTaskExecutor` presence/absence
- [ ] 1.5 — Listed all Thrift services (if any)
- [ ] 1.6 — Listed all decorators
- [ ] 1.7 — Documented all custom executors and thread pools
- [ ] 1.8 — Identified project-specific offloading patterns
- [ ] 1.9 — Built architecture map (entry points, safe wrappers, thread pools)

### Phase 2: Detection
- [ ] Category 1 — Searched for all direct blocking calls
- [ ] Category 2 — Searched for all blocking I/O operations
- [ ] Category 3 — Searched for synchronization on request paths
- [ ] Category 4 — Audited CompletableFuture continuation executors
- [ ] Category 5 — Audited thread pool configurations
- [ ] Category 6 — Checked for missing Armeria idioms (@Blocking, useBlockingTaskExecutor, cancel handlers, context propagation)
- [ ] Category 7 — Searched for transitive/hidden blocking (DNS, class loading, logging config)

### Phase 3: Analysis
- [ ] Traced call chains for ALL non-obvious findings
- [ ] Classified every finding by severity
- [ ] Excluded SAFE findings from the report (or listed them separately)

### Phase 4: Fix Planning
- [ ] Assigned a fix template (FP-1 through FP-8) to each non-SAFE finding
- [ ] Documented specific fix details for each finding

### Phase 5: Reporting
- [ ] Produced per-finding reports
- [ ] Produced summary table
- [ ] Produced statistics
- [ ] Included follow-up recommendations

---

## Common Mistakes to Avoid

- **Stopping at grep hits** — A grep hit is not a finding. You must trace the call chain to determine thread context. Many `.join()` calls are inside thread pool code and are perfectly safe.
- **Ignoring non-async CF continuations** — `.thenApply()` without `Async` is the most subtle source of event loop blocking. The continuation runs on whatever thread completes the source future.
- **Missing cancel handlers** — Cancel handler lambdas always run on the event loop. They're easy to overlook but are a common source of blocking.
- **Assuming `synchronized` is always bad** — It's only a problem if (a) it's on a request path reachable from the event loop AND (b) the lock can be contended by another thread holding it for a long time.
- **Forgetting transitive blocking** — A clean `serve()` method that calls `helperMethod()` which calls `utilMethod()` which calls `.join()` is still blocking the event loop.
- **Ignoring logging** — If the logging framework uses synchronous file appenders, every `log.info()` on the event loop is a blocking I/O operation. This is a configuration issue, not a code issue, but still critical.
