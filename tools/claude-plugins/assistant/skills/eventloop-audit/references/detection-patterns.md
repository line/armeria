# Detection Patterns — 7 Categories

Search the entire project (or scoped path) for each category of blocking pattern. Exclude test files (`**/test/**`, `**/tests/**`) and generated code (`**/generated/**`, `**/target/**`, `**/build/**`).

## Category 1: Direct Blocking Calls

These are explicit operations that block the calling thread.

**Search patterns:**

| Pattern | What it catches |
|---------|----------------|
| `\.join\(\)` | `CompletableFuture.join()` — blocks indefinitely |
| `\.get\(\s*\)` | `Future.get()` with no args — blocks indefinitely |
| `\.get\(\s*\d` | `Future.get(timeout, unit)` — bounded but still blocks |
| `Thread\.sleep\(` | `Thread.sleep()` — deliberate blocking |
| `\.await\(` | `CountDownLatch.await()`, `Condition.await()`, `CyclicBarrier.await()` |
| `\.acquire\(` | `Semaphore.acquire()` — blocks until permit available |
| `\.lock\(\)` | `ReentrantLock.lock()` — blocks until lock available |
| `\.lockInterruptibly\(\)` | Same, interruptible variant |
| `\.take\(\)` | `BlockingQueue.take()` — blocks until element available |
| `\.put\(` | `BlockingQueue.put()` — blocks if queue full (check context) |
| `\.wait\(` | `Object.wait()` — monitor wait |

**Triage for each hit:**
1. Identify the enclosing method
2. Trace callers to determine if reachable from an event loop entry point (from Phase 1)
3. Check if the call is inside a known safe execution wrapper
4. Classify severity (see Phase 3)

## Category 2: Blocking I/O Operations

I/O operations always block the calling thread. The question is whether they run on the event loop.

**2A — File system operations:**

| Pattern | What it catches |
|---------|----------------|
| `new FileInputStream` | File reading |
| `new FileOutputStream` | File writing |
| `new RandomAccessFile` | File random access |
| `FileChannel\.open` | File channel (blocking despite NIO package) |
| `Files\.(read\|write\|copy\|move\|walk\|list\|newInputStream\|newOutputStream)` | NIO file operations (still blocking despite being in `java.nio`) |
| `ProcessBuilder.*\.start\(\)` | Process execution |
| `Runtime\..*exec\(` | Process execution |

**2B — Network and HTTP operations:**

| Pattern | What it catches |
|---------|----------------|
| `\.openConnection\(\)` | `URL.openConnection()` — blocking HTTP |
| `\.openStream\(\)` | `URL.openStream()` — blocking HTTP |
| `HttpURLConnection` | Legacy blocking HTTP client |
| `HttpClient.*\.send\(` | `java.net.http.HttpClient.send()` — synchronous (`.sendAsync()` is safe) |
| `new Socket\(` | TCP socket creation |
| `new ServerSocket\(` | Server socket binding |
| `Pipe\.open\(\)` | NIO pipe creation — blocking despite NIO package |

**2C — Database operations:**

| Pattern | What it catches |
|---------|----------------|
| `DriverManager\.getConnection` | JDBC connection creation |
| `javax\.sql\.DataSource` | JDBC data source |
| `java\.sql\.\(Connection\|Statement\|PreparedStatement\|ResultSet\)` | JDBC operations |

**2D — Serialization and compression:**

| Pattern | What it catches |
|---------|----------------|
| `ObjectInputStream\|ObjectOutputStream` | Java serialization (blocking I/O on streams) |
| `GZIPInputStream\|GZIPOutputStream` | GZIP compression (blocking I/O) |
| `ZipInputStream\|ZipOutputStream` | ZIP operations (blocking I/O) |
| `DeflaterInputStream\|InflaterOutputStream` | Raw deflate/inflate (blocking I/O) |

**2E — Encryption and security:**

| Pattern | What it catches |
|---------|----------------|
| `KeyStore\.load\(` | Keystore loading from disk — blocking file I/O |
| `SSLContext\.getInstance` | SSL context initialization — can trigger I/O |
| `TrustManagerFactory\.init` | Trust manager init — may load certificates |

## Category 3: Synchronization That Could Contend on Event Loop

Locks are only a problem if they can contend (block waiting) on an event loop thread.

**Search patterns:**

| Pattern | What it catches |
|---------|----------------|
| `synchronized\s*\(` | Synchronized blocks |
| `\bsynchronized\b[^\n{;]*\(` | Synchronized methods (modifier in method signature) |
| `ReentrantLock` | Explicit locking |
| `ReentrantReadWriteLock` | Read-write locking |
| `StampedLock` | Stamped locking |

**Triage:**
- `synchronized` on startup/config-only paths → **SAFE**
- `synchronized` on request-path data accessed from event loop → **MEDIUM to HIGH** depending on contention probability
- `synchronized` on a monitor that is also locked by a thread pool thread → **HIGH** (event loop can wait for thread pool)

## Category 4: CompletableFuture Continuations on Wrong Executor

When you chain `.thenApply()` (without `Async`), the continuation runs on whichever thread completes the source future. If that's an event loop thread, the continuation is on the event loop.

**Search patterns (non-async variants — potentially dangerous):**

| Pattern | What it catches |
|---------|----------------|
| `\.thenApply\(` | Synchronous continuation — inherits completing thread |
| `\.thenAccept\(` | Same |
| `\.thenRun\(` | Same |
| `\.thenCompose\(` | Same |
| `\.whenComplete\(` | Same |
| `\.handle\(` | Same |
| `\.exceptionally\(` | Same |

**For each hit, determine:**
1. What completes the source future? (event loop thread? thread pool thread?)
2. Does the continuation body do blocking work?
3. If both answers are problematic → needs `*Async` variant with explicit executor

**Important — `*Async()` without executor is NOT always safe:**
```regex
\.thenApplyAsync\(
\.thenAcceptAsync\(
\.thenComposeAsync\(
```
The single-argument `*Async()` variants (e.g., `.thenApplyAsync(fn)`) use `ForkJoinPool.commonPool()`, NOT the blocking task executor. This is problematic if:
- The continuation does blocking I/O → blocks a common pool thread, starving other ForkJoinPool work
- The project expects `ServiceRequestContext` to be available → it won't be on ForkJoinPool threads

Only the two-argument variants (e.g., `.thenApplyAsync(fn, executor)`) with an explicit executor are reliably safe. Verify that the second argument is a blocking executor, not just any executor.

## Category 5: Thread Pool Misconfiguration

**Search patterns:**

| Pattern | Risk |
|---------|------|
| `Executors\.newCachedThreadPool` | Unbounded — can exhaust memory under load |
| `Executors\.newSingleThreadExecutor` | Single thread — no parallelism, instant bottleneck |
| `new ThreadPoolExecutor\(` | Check queue type and max size — unbounded `LinkedBlockingQueue` is risky |
| `new ForkJoinPool\(` | Check initialization is thread-safe, check parallelism level |

**Also verify:**
- Is `blockingTaskExecutor` configured in `ServerBuilder`? (If not, Armeria uses its default 200-thread pool)
- Is the blocking executor sized for the expected concurrent blocking operations?
- Are custom executors properly shut down on server close?

## Category 6: Missing Armeria Idioms

These are patterns where the project doesn't use Armeria's built-in mechanisms for handling blocking work.

**Check for:**

1. **gRPC services doing blocking work without `useBlockingTaskExecutor(true)`**
   - Find gRPC handler methods (from Phase 1.4)
   - Check if the method body contains I/O, database calls, or blocking waits
   - If yes and `useBlockingTaskExecutor(true)` is not set → **CRITICAL**

2. **Annotated services with blocking methods missing `@Blocking`**
   - Find `@Get`/`@Post`/etc. methods (from Phase 1.3)
   - Check if the method body contains blocking operations
   - If yes and `@Blocking` is absent → **CRITICAL**

3. **Custom `HttpService.serve()` that blocks**
   - Find `HttpService` implementations (from Phase 1.2)
   - Check if `serve()` body contains blocking operations without offloading to `ctx.blockingTaskExecutor()`
   - If yes → **CRITICAL**

4. **Cancel handlers that block**
   ```text
   Grep: setOnCancelHandler|whenRequestCancelling
   ```
   - Cancel handler lambdas run on the event loop
   - Check if they call `.get()`, `.join()`, I/O, or any blocking operation → **CRITICAL**

5. **Missing context propagation**
   ```text
   Grep: \.submit\(|\.execute\(|\.schedule\(
   ```
   - If work is submitted to a non-Armeria executor, check for `ctx.makeContextAware()` wrapping
   - Missing context propagation means `ServiceRequestContext.current()` will fail in the submitted task → **MEDIUM**

## Category 7: Transitive / Hidden Blocking

These are blocking operations that are easy to overlook.

**Search patterns:**

| Pattern | Risk |
|---------|------|
| `InetAddress\.getByName` | DNS resolution — can block for seconds |
| `InetAddress\.getAllByName` | Same |
| `Class\.forName` | Class loading — can trigger I/O to read class files |
| `ServiceLoader\.load` | Classpath scanning — iterates JARs |
| `System\.getProperty` | Usually fast, but can block with SecurityManager |

**Configuration-level checks:**
- **Logging framework**: Does the project use Logback or Log4j? Check `logback.xml` / `log4j2.xml` for synchronous appenders (`FileAppender`, `ConsoleAppender` without `AsyncAppender` wrapper). Every `log.info()` on the event loop becomes blocking I/O if appenders are synchronous.
  ```text
  Grep: <appender|<AsyncAppender|AsyncLogger
  Scope: **/logback*.xml, **/log4j2*.xml, **/logging*.xml
  ```
