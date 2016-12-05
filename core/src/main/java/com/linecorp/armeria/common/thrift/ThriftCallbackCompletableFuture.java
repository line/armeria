package com.linecorp.armeria.common.thrift;

import java.util.concurrent.CompletableFuture;

import org.apache.thrift.async.AsyncMethodCallback;

/**
 * A {@link CompletableFuture} that can be passed in as an {@link AsyncMethodCallback}
 * when making an asynchronous thrift client rpc.
 */
public class ThriftCallbackCompletableFuture<T> extends CompletableFuture<T> implements AsyncMethodCallback<T> {

    @Override
    public void onComplete(T t) {
        complete(t);
    }

    @Override
    public void onError(Exception e) {
        completeExceptionally(e);
    }
}
