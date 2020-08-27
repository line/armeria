/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A skeletal {@link Processor} implementation that decodes a stream of {@link HttpObject}s to N objects.
 */
@UnstableApi
public abstract class HttpDeframer<T> extends DefaultStreamMessage<T> implements Processor<HttpObject, T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpDeframer, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpDeframer.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpDeframer> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpDeframer.class, "initialized");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpDeframer> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpDeframer.class, "subscribed");

    private final ByteBufDeframerInput input;
    private final ArrayDeque<T> outputQueue = new ArrayDeque<>();

    private boolean sawLeadingHeaders;

    @Nullable
    private volatile EventExecutor eventLoop;
    @Nullable
    private volatile Subscription upstream;
    private volatile boolean cancelled;
    private volatile int initialized;
    private volatile int subscribed;

    /**
     * Returns a new {@link HttpDeframer} with the specified {@link EventExecutor} and
     * {@link ByteBufAllocator}.
     */
    protected HttpDeframer(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        input = new ByteBufDeframerInput(alloc);
    }

    /**
     * Converts the specified {@link HttpData} to {@link ByteBuf}.
     */
    protected ByteBuf convertToByteBuf(HttpData data) {
        return data.byteBuf();
    }

    /**
     * Decodes a stream of {@link HttpData}s to N objects.
     * This method will be called whenever an {@link HttpData} is signaled from {@link Publisher}.
     */
    protected abstract void process(HttpDeframerInput in, HttpDeframerOutput<T> out);

    private void process(HttpObject data) {
        if (data instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) data;

            if (headers instanceof ResponseHeaders) {
                final ResponseHeaders responseHeaders = (ResponseHeaders) headers;
                if (responseHeaders.status().isInformational()) {
                    processInformationalHeaders(responseHeaders, outputQueue::addLast);
                    return;
                }
            }

            if (!sawLeadingHeaders) {
                sawLeadingHeaders = true;
                processHeaders((HttpHeaders) data, outputQueue::addLast);
            } else {
                processTrailers((HttpHeaders) data, outputQueue::addLast);
            }
            return;
        }

        if (data instanceof HttpData) {
            input.add(convertToByteBuf((HttpData) data));
            process(input, outputQueue::addLast);
            input.discardReadBytes();
        }
    }

    /**
     * Decodes an informational {@link ResponseHeaders} to N objects.
     */
    protected void processInformationalHeaders(ResponseHeaders in, HttpDeframerOutput<T> out) {}

    /**
     * Decodes an non-informational {@link HttpHeaders} to N objects.
     */
    protected void processHeaders(HttpHeaders in, HttpDeframerOutput<T> out) {}

    /**
     * Decodes a {@link HttpHeaders trailers} to N objects.
     */
    protected void processTrailers(HttpHeaders in, HttpDeframerOutput<T> out) {}

    /**
     * Invoked when a {@link Throwable} is raised while deframing.
     */
    protected void processOnError(Throwable cause) {}

    @Override
    final SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        final SubscriptionImpl subscriptionImpl = super.subscribe(subscription);
        if (subscribedUpdater.compareAndSet(this, 0, 1)) {
            eventLoop = subscription.executor();
            deferredInit();
        }
        return subscriptionImpl;
    }

    private void deferredInit() {
        final Subscription upstream = this.upstream;
        if (upstream != null && subscribed != 0) {
            if (initializedUpdater.compareAndSet(this, 0, 1)) {
                if (demand() > 0) {
                    upstream.request(1);
                }
            }
        }
    }

    @Override
    void request(long n) {
        if (initialized != 0 && outputQueue.isEmpty()) {
            upstream.request(1);
        }
        super.request(n);
    }

    @Override
    void cancel() {
        cancelAndCleanup();
        super.cancel();
    }

    @Override
    public final void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (upstreamUpdater.compareAndSet(this, null, subscription)) {
            deferredInit();
        } else {
            subscription.cancel();
        }
    }

    @Override
    public final void onNext(HttpObject data) {
        final EventExecutor eventLoop = this.eventLoop;
        if (eventLoop.inEventLoop()) {
            onNext0(data);
        } else {
            eventLoop.execute(() -> onNext0(data));
        }
    }

    private void onNext0(HttpObject data) {
        try {
            process(data);

            final Subscription upstream = this.upstream;
            if (outputQueue.isEmpty()) {
                upstream.request(1);
            } else {
                for (;;) {
                    final T deframed = outputQueue.poll();
                    if (deframed != null) {
                        write(deframed);
                    } else {
                        break;
                    }
                }
                whenConsumed().thenRun(() -> {
                    if (demand() > 0) {
                        upstream.request(1);
                    }
                });
            }
            input.discardReadBytes();
        } catch (Throwable ex) {
            Exceptions.throwIfFatal(ex);
            processOnError(ex);
            cancelAndCleanup();
            abort(ex);
        }
    }

    private void cancelAndCleanup() {
        cancelled = true;
        final Subscription upstream = this.upstream;
        if (upstream != null) {
            upstream.cancel();
        }
        cleanup();
    }

    @Override
    public final void onError(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cancelled) {
            return;
        }
        final EventExecutor eventLoop = this.eventLoop;
        if (!eventLoop.inEventLoop()) {
            this.eventLoop.execute(() -> onError(cause));
            return;
        }

        processOnError(cause);
        cleanup();
        abort(cause);
    }

    @Override
    public final void onComplete() {
        if (cancelled) {
            return;
        }

        final EventExecutor eventLoop = this.eventLoop;
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::onComplete);
            return;
        }

        cleanup();
        close();
    }

    private void cleanup() {
        input.clear();
    }
}
