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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMessage;
import com.linecorp.armeria.common.SplitHttpMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

abstract class AbstractSplitHttpMessage implements SplitHttpMessage, StreamMessage<HttpData> {

    private static final AtomicIntegerFieldUpdater<AbstractSplitHttpMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AbstractSplitHttpMessage.class, "subscribed");

    private volatile int subscribed;

    private final HttpMessage upstream;
    private final EventExecutor upstreamExecutor;
    private final SplitHttpMessageSubscriber bodySubscriber;

    AbstractSplitHttpMessage(HttpMessage upstream, EventExecutor upstreamExecutor,
                                       SplitHttpMessageSubscriber bodySubscriber) {
        this.upstream = requireNonNull(upstream, "upstream");
        this.upstreamExecutor = requireNonNull(upstreamExecutor, "upstreamExecutor");
        this.bodySubscriber = bodySubscriber;
        upstream.subscribe(bodySubscriber, this.upstreamExecutor, SubscriptionOption.values());
    }

    protected final HttpMessage upstream() {
        return upstream;
    }

    @Override
    public final StreamMessage<HttpData> body() {
        return this;
    }

    @Override
    public final CompletableFuture<HttpHeaders> trailers() {
        return bodySubscriber.trailersFuture();
    }

    @Override
    public final boolean isOpen() {
        return upstream.isOpen();
    }

    @Override
    public final boolean isEmpty() {
        return !isOpen() && !bodySubscriber.wroteAny();
    }

    @Override
    public final long demand() {
        return upstream.demand();
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return upstream.whenComplete();
    }

    @Override
    public final void abort() {
        upstream.abort();
    }

    @Override
    public final void abort(Throwable cause) {
        upstream.abort(cause);
    }

    @Override
    public final EventExecutor defaultSubscriberExecutor() {
        return upstreamExecutor;
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("Only single subscriber is allowed!"));
            return;
        }

        if (upstreamExecutor.inEventLoop()) {
            bodySubscriber.initDownstream(subscriber, executor, options);
        } else {
            upstreamExecutor.execute(() -> bodySubscriber.initDownstream(subscriber, executor, options));
        }
    }
}
