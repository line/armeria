package com.linecorp.armeria.common.thrift;

public final class ThriftFutures {
    /**
     * Returns a new {@link ThriftCompletableFuture} instance that has its value set immediately.
     */
    public static <T> ThriftCompletableFuture<T> successfulCompletedFuture(T value) {
        ThriftCompletableFuture<T> future = new ThriftCompletableFuture<T>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftCompletableFuture} instance that has an exception set immediately.
     */
    public static <T> ThriftCompletableFuture<T> failedCompletedFuture(Exception e) {
        ThriftCompletableFuture<T> future = new ThriftCompletableFuture<>();
        future.onError(e);
        return future;
    }

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has its value set immediately.
     */
    public static <T> ThriftListenableFuture<T> successfulListenableFuture(T value) {
        ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onComplete(value);
        return future;
    }

    /**
     * Returns a new {@link ThriftListenableFuture} instance that has an exception set immediately.
     */
    public static <T> ThriftListenableFuture<T> failedListenableFuture(Exception e) {
        ThriftListenableFuture<T> future = new ThriftListenableFuture<>();
        future.onError(e);
        return future;
    }

    private ThriftFutures() {}
}
