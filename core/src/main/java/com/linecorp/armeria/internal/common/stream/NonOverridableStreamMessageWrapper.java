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

package com.linecorp.armeria.internal.common.stream;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageDuplicator;
import com.linecorp.armeria.common.stream.StreamMessageWrapper;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

public class NonOverridableStreamMessageWrapper<T, D extends StreamMessageDuplicator<T>>
        extends StreamMessageWrapper<T> {

    protected NonOverridableStreamMessageWrapper(StreamMessage<? extends T> delegate) {
        super(delegate);
    }

    @Override
    public final boolean isOpen() {
        return super.isOpen();
    }

    @Override
    public final boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public final long demand() {
        return super.demand();
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return super.whenComplete();
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        super.subscribe(subscriber, executor, options);
    }

    @Override
    public final EventExecutor defaultSubscriberExecutor() {
        return super.defaultSubscriberExecutor();
    }

    @Override
    public final void abort() {
        super.abort();
    }

    @Override
    public final void abort(Throwable cause) {
        super.abort(cause);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final D toDuplicator() {
        return (D) super.toDuplicator();
    }

    @SuppressWarnings("unchecked")
    @Override
    public final D toDuplicator(EventExecutor executor) {
        return (D) super.toDuplicator(executor);
    }
}
