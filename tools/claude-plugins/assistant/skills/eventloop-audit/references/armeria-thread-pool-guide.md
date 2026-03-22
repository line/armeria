# Armeria Thread Pool Guide for Developers

## Context

Armeria is built on Netty's non-blocking I/O model. Misusing thread pools — especially blocking the event loop — is the most common cause of performance degradation and deadlocks. This guide provides an architectural overview, recommended configurations, and practical dos/don'ts for developers working with Armeria.

---

## 1. Architectural Overview

### Thread Pool Roles

| Pool | What it does | Thread name pattern | Default size | Daemon? |
|------|-------------|---------------------|-------------|---------|
| **bossGroup** | Accepts incoming TCP connections (one per server port) | `armeria-boss-{port}` | **1 per port** (fixed, not configurable) | No |
| **workerGroup** | Handles all socket I/O (reads/writes) and executes non-blocking service logic | `armeria-common-worker-*` | `2 * CPU cores` (NIO/epoll/kqueue) or `CPU cores` (io_uring) | Yes |
| **serviceWorkerGroup** | Optional dedicated EventLoopGroup for service method execution, isolating service logic from socket I/O | User-defined | Falls back to workerGroup if not set | Yes |
| **blockingTaskExecutor** | Runs long-running or blocking operations (DB calls, file I/O, legacy sync APIs) | `armeria-common-blocking-tasks-*` | **200** threads (inspired by Tomcat default) | Yes |

### How a Request Flows Through the Pools

```
Client request
    |
    v
[bossGroup]  -- accepts TCP connection, hands channel to workerGroup
    |
    v
[workerGroup]  -- reads bytes, decodes HTTP/2 frames, invokes service
    |
    +---> Non-blocking service: runs directly on event loop thread
    |
    +---> @Blocking / useBlockingTaskExecutor(true): dispatched to blockingTaskExecutor
    |
    +---> serviceWorkerGroup configured: service runs on dedicated event loop
```

### Key Classes and Files

- `CommonPools` — global shared pools (`core/.../common/CommonPools.java`)
- `EventLoopGroups` — factory for creating EventLoopGroups (`core/.../common/util/EventLoopGroups.java`)
- `BlockingTaskExecutor` / `BlockingTaskExecutorBuilder` — blocking pool abstraction (`core/.../common/util/`)
- `EventLoopThread` — custom thread extending Netty's `FastThreadLocalThread`, implements `NonBlocking` marker (`core/.../internal/common/util/EventLoopThread.java`)
- `EventLoopCheckingFuture` — warns when `.get()`/`.join()` is called from an event loop (`core/.../common/util/EventLoopCheckingFuture.java`)
- `DefaultAnnotatedService` — dispatches to event loop or blocking executor based on `@Blocking` (`core/.../internal/server/annotation/DefaultAnnotatedService.java`)
- `Flags` / `DefaultFlagsProvider` — system-wide defaults and JVM flag overrides (`core/.../common/Flags.java`)

### Client-Side Thread Pools

The HTTP client (`WebClient`) uses the same `CommonPools.workerGroup()` by default. An `EventLoopScheduler` distributes connections across event loops per endpoint (default: 1 event loop per endpoint for HTTP/2, configurable for HTTP/1.1).

Key client config class: `ClientFactoryBuilder` (`core/.../client/ClientFactoryBuilder.java`)

---

## 2. `serviceWorkerGroup` vs `blockingTaskExecutor` — When to Use Which

These two are the most commonly confused. They solve **different problems** and have fundamentally different runtime characteristics.

### The Core Distinction

| Aspect | `serviceWorkerGroup` | `blockingTaskExecutor` |
|--------|----------------------|------------------------|
| **Java type** | `EventLoopGroup` (Netty event loops) | `ScheduledExecutorService` (thread pool) |
| **Thread type** | Event loop threads (`NonBlocking`) | Regular threads (blocking allowed) |
| **Can you block in it?** | **NO** — same rules as workerGroup | **YES** — that's its purpose |
| **Scheduling model** | Single-threaded per loop, run-to-completion | Traditional thread pool, one thread per task |
| **Typical size** | Small (matches CPU cores) | Large (default 200) |

### How They Work at Runtime

**serviceWorkerGroup** — The service method still runs on an event loop, just a *different* one from the I/O channel. In `HttpServerHandler`, Armeria checks whether the configured serviceWorkerGroup differs from the main workerGroup. If so, it calls `req.subscribeOn(serviceEventLoop)` and invokes the service on that loop. Request reading, service execution, and response emission all happen on the service event loop. Response *writing* to the socket still goes back to the channel's original event loop.

```
[workerGroup event loop]  reads bytes from socket
        |
        v  (if serviceWorkerGroup != workerGroup)
[serviceWorkerGroup event loop]  decodes request, runs service, emits response
        |
        v  (response writing)
[workerGroup event loop]  writes bytes to socket
```

**blockingTaskExecutor** — The service method is submitted via `thenApplyAsync(..., ctx.blockingTaskExecutor())` to a traditional thread pool. The calling event loop is freed immediately. The blocking thread runs the service logic, and when done, the response flows back to the event loop for writing.

```
[event loop]  reads bytes, decodes request
        |
        v  (thenApplyAsync)
[blocking thread pool]  runs service method (blocking OK here)
        |
        v  (future completes)
[event loop]  writes response bytes to socket
```

### Decision Guide

**Use `serviceWorkerGroup` when:**

- Your service does **CPU-intensive but non-blocking** work (e.g., JSON serialization of large payloads, image transformation in memory, complex computation)
- You want to **prevent service CPU work from starving socket I/O** — if your service burns 50ms of CPU per request, that delays read/write for all other connections sharing that event loop
- You need **isolation between services** — e.g., a high-throughput service shouldn't compete for event loop time with a latency-sensitive one
- Your code is already fully async (returns `CompletableFuture`, uses reactive streams) — you just want it on a different event loop

```java
// Good use case: CPU-heavy serialization isolated from I/O
Server.builder()
      .serviceWorkerGroup(4)  // Dedicated event loops for service logic
      .service("/heavy-json", (ctx, req) -> {
          // Non-blocking but CPU-intensive: serialize huge object
          return HttpResponse.of(hugeObjectMapper.writeValueAsBytes(bigData));
      })
      .build();
```

**Use `blockingTaskExecutor` when:**

- Your code calls **synchronous blocking APIs** (JDBC, file I/O, `Thread.sleep`, `future.get()`, legacy HTTP clients, `synchronized` blocks)
- You're integrating with **libraries that block the calling thread** (most traditional Java libraries)
- You have **no choice** but to wait for a result synchronously

```java
// Good use case: JDBC call that blocks the thread
Server.builder()
      .service("/users", (ctx, req) ->
          HttpResponse.of(CompletableFuture.supplyAsync(() -> {
              // This blocks — JDBC has no async API
              User user = jdbcTemplate.queryForObject("SELECT ...", User.class);
              return HttpResponse.of(HttpStatus.OK, MediaType.JSON, toJson(user));
          }, ctx.blockingTaskExecutor())))
      .build();
```

**DON'T use `serviceWorkerGroup` for blocking work** — it's still an event loop. Blocking it has the same catastrophic effect as blocking the main workerGroup. You're just blocking a *different* event loop.

**DON'T use `blockingTaskExecutor` for CPU-bound async work** — it wastes a thread sitting idle during async operations and doesn't benefit from event loop optimizations (FastThreadLocal, zero-copy buffers, etc.).

### When You Need Neither

Most async services need neither. If your service just orchestrates async HTTP calls, reactive streams, or CompletableFuture chains, let it run on the default workerGroup.

---

## 3. Recommended Configuration Settings

### Sizing Guidelines

| Pool | Guideline |
|------|-----------|
| **workerGroup** | Default (`2 * CPU`) is usually fine. Increase only if profiling shows event loop saturation on I/O-heavy workloads. |
| **blockingTaskExecutor** | Default 200 is generous. Size based on expected concurrent blocking operations. Consider: `expected_concurrent_blocking_calls * avg_blocking_duration / target_latency`. |
| **serviceWorkerGroup** | Only configure if you need to isolate service execution from socket I/O (e.g., CPU-heavy request processing that shouldn't delay I/O). Most services don't need this. |

### JVM Flags

```
-Dcom.linecorp.armeria.numCommonWorkers=<int>           # Override worker group size
-Dcom.linecorp.armeria.numCommonBlockingTaskThreads=<int> # Override blocking executor size
-Dcom.linecorp.armeria.reportBlockedEventLoop=true       # Enable event loop blocking warnings (default: true)
```

### Server Builder Example

```java
Server.builder()
      // Custom worker group (only if default is insufficient)
      .workerGroup(16)
      // Custom blocking executor for this server
      .blockingTaskExecutor(BlockingTaskExecutor.builder()
              .numThreads(100)
              .threadNamePrefix("my-app-blocking")
              .build(), true)
      .service("/api", myService)
      .build();
```

### Client Factory Example

```java
ClientFactory.builder()
      .workerGroup(8)
      // Distribute HTTP/2 connections across more event loops for high-throughput endpoints
      .maxNumEventLoopsPerEndpoint(4)
      .build();
```

---

## 4. Dos and Don'ts

### DO: Use `@Blocking` for Synchronous Service Methods

```java
// Annotated service — method level
@Get("/users/{id}")
@Blocking
public User getUser(@Param int id) {
    return database.findById(id); // Safe: runs on blocking executor
}

// Annotated service — class level (all methods become blocking)
@Blocking
public class MyDatabaseService {
    @Get("/items")
    public List<Item> list() { return db.listAll(); }
}
```

### DO: Use `useBlockingTaskExecutor(true)` for gRPC/Thrift Services

```java
// gRPC
Server.builder()
      .service(GrpcService.builder()
              .addService(new MyBlockingGrpcService())
              .useBlockingTaskExecutor(true)
              .build());

// Thrift
Server.builder()
      .service("/thrift", THttpService.builder()
              .addService(new MyBlockingThriftService())
              .useBlockingTaskExecutor(true)
              .build());
```

### DO: Use `ctx.blockingTaskExecutor()` for Ad-Hoc Blocking Work

```java
@Get("/report")
public CompletableFuture<HttpResponse> generateReport(ServiceRequestContext ctx) {
    return CompletableFuture.supplyAsync(() -> {
        byte[] pdf = slowPdfGenerator.generate(); // blocking
        return HttpResponse.of(HttpStatus.OK, MediaType.PDF, pdf);
    }, ctx.blockingTaskExecutor());
}
```

The `ctx.blockingTaskExecutor()` returns a context-aware executor — it automatically propagates `ServiceRequestContext` to the blocking thread, so logging, tracing, and `ServiceRequestContext.current()` work correctly.

### DO: Stay Async When Possible

```java
// Prefer returning async types on the event loop
@Get("/data")
public CompletableFuture<String> getData() {
    return asyncHttpClient.get("/upstream")
            .thenApply(response -> response.contentUtf8());
    // Runs entirely on event loop — no blocking, no thread switch
}
```

### DON'T: Block the Event Loop

```java
// NEVER do this on the event loop
@Get("/bad")
public String bad() {
    Thread.sleep(1000);                    // Blocks event loop
    return db.query("SELECT ...");         // Blocks event loop
    future.get();                          // Blocks event loop
    future.join();                         // Blocks event loop
    synchronized(lock) { ... }             // Can block event loop
    new URL(...).openConnection();         // Blocks event loop
}
```

Armeria will log a warning if you call `.get()` or `.join()` on an `EventLoopCheckingFuture` from an event loop thread. Treat these warnings as bugs.

### DON'T: Create Unbounded Thread Pools for Blocking Work

```java
// Don't do this — use Armeria's blockingTaskExecutor instead
ExecutorService myPool = Executors.newCachedThreadPool();
myPool.submit(() -> { ... });
// Problems: no context propagation, no metrics, no coordinated shutdown
```

### DON'T: Mix Blocking and Non-Blocking in the Same Service Without Annotation

```java
// BAD: one blocking method taints the whole event loop experience
public class MixedService {
    @Get("/fast")
    public String fast() { return "ok"; }

    @Get("/slow")
    public String slow() {
        return db.query("..."); // Blocks! But no @Blocking annotation
    }
}
```

Fix: add `@Blocking` to the slow method, or use `ctx.blockingTaskExecutor()` explicitly.

### DON'T: Assume `serviceWorkerGroup` is Required

Most applications should NOT configure `serviceWorkerGroup`. It adds complexity and is only useful when:
- Your service does CPU-intensive work (e.g., serialization of large payloads) that could starve socket I/O
- You want strict isolation between I/O handling and request processing

For blocking work, use `blockingTaskExecutor` instead — that's what it's for.

### DON'T: Forget Context Propagation When Switching Threads

```java
// BAD: loses ServiceRequestContext
someOtherExecutor.submit(() -> {
    ServiceRequestContext.current(); // Throws IllegalStateException!
});

// GOOD: use context-aware executor
ctx.blockingTaskExecutor().submit(() -> {
    ServiceRequestContext.current(); // Works
});

// GOOD: if you must use a custom executor, propagate context manually
someOtherExecutor.submit(ctx.makeContextAware(() -> {
    ServiceRequestContext.current(); // Works
}));
```

---

## 5. Programmatic Usage (Without Annotations)

When you implement `HttpService` directly, build decorators, or work with gRPC/Thrift at a lower level, you manage executor dispatch yourself.

### 5.1 Direct `HttpService` with Blocking Work

```java
// Implement HttpService directly — you control everything
HttpService myService = (ctx, req) -> {
    CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    ctx.blockingTaskExecutor().execute(() -> {
        try {
            String result = legacySyncClient.call(); // blocking
            future.complete(HttpResponse.of(HttpStatus.OK, MediaType.JSON, result));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    });
    return HttpResponse.of(future);
};

Server.builder().service("/api", myService).build();
```

### 5.2 CompletableFuture Chaining with Explicit Executors

Use `thenApplyAsync` / `thenComposeAsync` / `supplyAsync` with the right executor to control where each stage runs.

```java
HttpService myService = (ctx, req) -> {
    // Stage 1: blocking DB call on blocking executor
    CompletableFuture<List<Long>> ids = CompletableFuture.supplyAsync(
            () -> {
                assert ServiceRequestContext.current() == ctx;  // context is propagated
                assert !ctx.eventLoop().inEventLoop();           // not on event loop
                return jdbcTemplate.queryForList("SELECT id FROM items", Long.class);
            },
            ctx.blockingTaskExecutor());

    // Stage 2: async HTTP calls back on event loop
    CompletableFuture<List<AggregatedHttpResponse>> details = ids.thenComposeAsync(
            idList -> {
                assert ctx.eventLoop().inEventLoop();  // back on event loop
                List<CompletableFuture<AggregatedHttpResponse>> futures = idList.stream()
                        .map(id -> backendClient.get("/items/" + id).aggregate())
                        .collect(Collectors.toList());
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream()
                                .map(CompletableFuture::join)
                                .collect(Collectors.toList()));
            },
            ctx.eventLoop());

    // Stage 3: assemble response (still on event loop)
    CompletableFuture<HttpResponse> response = details.thenApply(
            results -> HttpResponse.of(HttpStatus.OK, MediaType.JSON, toJson(results)));

    return HttpResponse.of(response);
};
```

### 5.3 Scheduling Delayed Work on the Event Loop

Use `ctx.eventLoop().schedule()` for non-blocking timed operations.

```java
// gRPC: respond after a delay without blocking a thread
ServiceRequestContext.current().eventLoop().schedule(() -> {
    responseObserver.onNext(buildReply(request.getName()));
    responseObserver.onCompleted();
}, 3, TimeUnit.SECONDS);
```

### 5.4 gRPC: Submit Blocking Work Manually

When you don't want `useBlockingTaskExecutor(true)` for the entire service, submit individual calls.

```java
public class HelloServiceImpl extends HelloServiceGrpc.HelloServiceImplBase {
    @Override
    public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // This method runs on the event loop by default.
        // Submit only the blocking part:
        ServiceRequestContext.current().blockingTaskExecutor().submit(() -> {
            try {
                Thread.sleep(3000); // Simulate blocking API
            } catch (Exception ignored) {}
            responseObserver.onNext(buildReply(request.getName()));
            responseObserver.onCompleted();
        });
    }
}
```

### 5.5 Thrift: Submit Blocking Work Manually

Same pattern as gRPC.

```java
ServiceRequestContext.current().blockingTaskExecutor().execute(() -> {
    try {
        Thread.sleep(3000); // Simulate blocking
    } catch (Exception ignored) {}
    resultHandler.onComplete(buildReply(request.getName()));
});
```

### 5.6 Decorators with Executor Awareness

```java
// A decorator that logs request duration, running the delegate as-is
HttpService decorated = myService.decorate((delegate, ctx, req) -> {
    long start = System.nanoTime();
    return delegate.serve(ctx, req).mapHeaders(headers -> {
        long elapsed = System.nanoTime() - start;
        logger.info("{} took {} ms", ctx.path(), elapsed / 1_000_000);
        return headers;
    });
});
```

### 5.7 Context Propagation with `makeContextAware`

When you must use a third-party executor that Armeria doesn't control, wrap your task.

```java
// Wrapping a Runnable
someExternalExecutor.submit(ctx.makeContextAware(() -> {
    ServiceRequestContext.current(); // Works — context is mounted
    doWork();
}));

// Wrapping a Callable
Future<String> result = someExternalExecutor.submit(ctx.makeContextAware(() -> {
    return ServiceRequestContext.current().path(); // Works
}));

// Wrapping a Function (for CompletableFuture chains)
future.thenApply(ctx.makeContextAware(value -> {
    ServiceRequestContext.current(); // Works
    return transform(value);
}));
```

### 5.8 Reactor Integration

```java
HttpService myService = (ctx, req) -> {
    Scheduler blockingScheduler = Schedulers.fromExecutor(ctx.blockingTaskExecutor());
    Scheduler eventLoopScheduler = Schedulers.fromExecutor(ctx.eventLoop());

    Mono<String> result = Mono.fromCallable(() -> {
                // Runs on blocking scheduler
                return database.query("SELECT ...");
            })
            .subscribeOn(blockingScheduler)
            .flatMap(dbResult ->
                // Switch back to event loop for async HTTP call
                Mono.fromCompletionStage(backendClient.get("/enrich/" + dbResult).aggregate())
                    .subscribeOn(eventLoopScheduler)
                    .map(resp -> resp.contentUtf8())
            );

    return HttpResponse.of(result.map(body ->
            HttpResponse.of(HttpStatus.OK, MediaType.JSON, body)).toFuture());
};
```

### 5.9 RxJava Integration

```java
Scheduler blockingScheduler = Schedulers.from(ctx.blockingTaskExecutor());
Scheduler eventLoopScheduler = Schedulers.from(ctx.eventLoop());

Single<String> result = Single.fromCallable(() -> database.query("SELECT ..."))
        .subscribeOn(blockingScheduler)       // blocking work here
        .observeOn(eventLoopScheduler)        // switch to event loop
        .flatMap(dbResult ->
            Single.fromCompletionStage(backendClient.get("/enrich/" + dbResult).aggregate())
                  .map(AggregatedHttpResponse::contentUtf8));
```

---

## Quick Reference: Which Pool to Use

| Scenario | Pool | How |
|----------|------|-----|
| HTTP/gRPC request handling (async) | workerGroup (event loop) | Default — just return `CompletableFuture` or reactive types |
| Database queries, file I/O, sync APIs | blockingTaskExecutor | `@Blocking`, `useBlockingTaskExecutor(true)`, or `ctx.blockingTaskExecutor()` |
| CPU-heavy non-blocking work that shouldn't starve I/O | serviceWorkerGroup | `ServerBuilder.serviceWorkerGroup(n)` (rare) |
| Client HTTP calls | workerGroup (event loop) | Default — `WebClient` is async |
| Legacy synchronous library calls | blockingTaskExecutor | Wrap with `ctx.blockingTaskExecutor().submit(...)` |
| Delayed/scheduled non-blocking work | event loop | `ctx.eventLoop().schedule(...)` |
| Reactor blocking operations | blockingTaskExecutor | `.subscribeOn(Schedulers.fromExecutor(ctx.blockingTaskExecutor()))` |
| Custom third-party executor | context-wrap it | `ctx.makeContextAware(runnable)` |
