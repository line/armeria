# Fix Plan Templates (FP-1 through FP-8)

For each non-SAFE finding, recommend one of these fix approaches. **Document the recommendation — do not apply it.**

## FP-1: Replace `.join()`/`.get()` with Async Chaining

```java
// BEFORE (blocking on event loop):
String result = asyncOp().join();
return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);

// AFTER (non-blocking — return a future-backed response):
return HttpResponse.of(
    asyncOp().thenApply(result ->
        HttpResponse.of(HttpStatus.OK, MediaType.JSON, result)
    )
);
```

## FP-2: Offload to `blockingTaskExecutor`

```java
// BEFORE (blocking on event loop):
public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
    byte[] data = readFileSync(); // blocks
    return HttpResponse.of(HttpStatus.OK, MediaType.OCTET_STREAM, data);
}

// AFTER (offloaded to blocking executor):
public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
    return HttpResponse.of(
        CompletableFuture.supplyAsync(
            () -> HttpResponse.of(HttpStatus.OK, MediaType.OCTET_STREAM, readFileSync()),
            ctx.blockingTaskExecutor()
        )
    );
}
```

## FP-3: Add `@Blocking` Annotation

```java
// For annotated services with blocking methods:
@Get("/data")
@Blocking
public String getData() {
    return database.query("SELECT ..."); // now dispatched to blocking executor
}
```

## FP-4: Enable `useBlockingTaskExecutor(true)` for gRPC/Thrift

```java
GrpcService.builder()
    .addService(myBlockingService)
    .useBlockingTaskExecutor(true)  // all handler methods run on blocking executor
    .build();
```

## FP-5: Use Async CF Variant with Explicit Executor

```java
// BEFORE (continuation inherits completing thread — may be event loop):
future.thenApply(result -> blockingTransform(result));

// AFTER (explicit executor for blocking work):
future.thenApplyAsync(result -> blockingTransform(result), ctx.blockingTaskExecutor());
```

## FP-6: Add Timeout to Unavoidable Blocking

```java
// BEFORE (indefinite block):
future.get();

// AFTER (bounded with proper error handling):
try {
    future.get(10, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // handle timeout gracefully
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("Interrupted", e);
}
```

## FP-7: Make Thread Pool Initialization Thread-Safe

```java
// BEFORE (race condition on lazy init):
if (executor == null) {
    executor = new ForkJoinPool(parallelism);
}

// AFTER (double-checked locking):
private volatile ExecutorService executor;

private ExecutorService getExecutor(int parallelism) {
    ExecutorService exec = this.executor;
    if (exec == null) {
        synchronized (this) {
            exec = this.executor;
            if (exec == null) {
                exec = new ForkJoinPool(parallelism);
                this.executor = exec;
            }
        }
    }
    return exec;
}
```

## FP-8: Wire Context Propagation

```java
// BEFORE (loses ServiceRequestContext on custom executor):
customExecutor.submit(() -> {
    ServiceRequestContext.current(); // throws IllegalStateException
});

// AFTER (context-aware):
customExecutor.submit(ctx.makeContextAware(() -> {
    ServiceRequestContext.current(); // works
}));
```
