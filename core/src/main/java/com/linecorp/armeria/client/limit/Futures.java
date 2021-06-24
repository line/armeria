package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletableFuture;

import io.netty.util.concurrent.Future;

public class Futures {
    public static <V> CompletableFuture<V> toCompletableFuture(Future<V> future) {
        CompletableFuture<V> adapter = new CompletableFuture<>();
        if (future.isDone()) {
            if (future.isSuccess()) {
                adapter.complete(future.getNow());
            } else {
                adapter.completeExceptionally(future.cause());
            }
        } else {
            future.addListener((Future<V> f) -> {
                if (f.isSuccess()) {
                    adapter.complete(f.getNow());
                } else {
                    adapter.completeExceptionally(f.cause());
                }
            });
        }
        return adapter;
    }
}
