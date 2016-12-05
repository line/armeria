package com.linecorp.armeria.common.thrift;

import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A {@link ListenableFuture} that can be passed in as an {@link AsyncMethodCallback}
 * when making an asynchronous thrift client rpc.
 */
public class ThriftCallbackListenableFuture<T> extends AbstractFuture<T> implements AsyncMethodCallback<T> {

    @Override
    public void onComplete(T t) {
        set(t);
    }

    @Override
    public void onError(Exception e) {
        setException(e);
    }
}
