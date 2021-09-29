/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * TBU.
 */
@UnstableApi
public class DelegatingStreamMessage<T> implements StreamMessage<T>, StreamWriter<T>,
                                                   StreamCallbackListener<T> {
    private final StreamMessageAndWriter<T> delegate;

    /**
     * TBU.
     */
    public DelegatingStreamMessage(StreamMessageAndWriter<T> delegate) {
        this.delegate = delegate;
        delegate.setCallbackListener(this);
    }

    protected StreamMessageAndWriter<T> delegate() {
        return delegate;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean tryWrite(T o) {
        return delegate.tryWrite(o);
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        return delegate.whenConsumed();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Throwable cause) {
        delegate.close(cause);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long demand() {
        return delegate.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }
}
