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
import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
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
public final class HttpDeframer<T> extends DefaultStreamMessage<T> implements Processor<HttpObject, T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpDeframer, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpDeframer.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpDeframer> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpDeframer.class, "initialized");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpDeframer> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpDeframer.class, "subscribed");

    private final ArrayDeque<T> outputQueue = new ArrayDeque<>();
    private final HttpDeframerHandler<T> handler;
    private final ByteBufDeframerInput input;
    private final Function<? super HttpData, ? extends ByteBuf> byteBufConverter;

    private boolean sawLeadingHeaders;

    @Nullable
    private volatile EventExecutor eventLoop;
    @Nullable
    private volatile Subscription upstream;
    private volatile boolean cancelled;
    private volatile int initialized;
    private volatile int subscribed;

    /**
     * Returns a new {@link HttpDeframer} with the specified {@link HttpDeframerHandler} and
     * {@link ByteBufAllocator}.
     */
    public HttpDeframer(HttpDeframerHandler<T> handler, ByteBufAllocator alloc) {
        this(handler, alloc, HttpData::byteBuf);
    }

    /**
     * Returns a new {@link HttpDeframer} with the specified {@link HttpDeframerHandler},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    public HttpDeframer(HttpDeframerHandler<T> handler, ByteBufAllocator alloc,
                        Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        this.handler = requireNonNull(handler, "handler");
        input = new ByteBufDeframerInput(requireNonNull(alloc, "alloc"));
        this.byteBufConverter = byteBufConverter;
    }

    private void process(HttpObject data) {
        if (data instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) data;

            if (headers instanceof ResponseHeaders) {
                final ResponseHeaders responseHeaders = (ResponseHeaders) headers;
                if (responseHeaders.status().isInformational()) {
                    handler.processInformationalHeaders(responseHeaders, outputQueue::addLast);
                    return;
                }
            }

            if (!sawLeadingHeaders) {
                sawLeadingHeaders = true;
                handler.processHeaders((HttpHeaders) data, outputQueue::addLast);
            } else {
                handler.processTrailers((HttpHeaders) data, outputQueue::addLast);
            }
            return;
        }

        if (data instanceof HttpData) {
            final ByteBuf byteBuf = byteBufConverter.apply((HttpData) data);
            requireNonNull(byteBuf, "byteBufConverter returned null");
            input.add(byteBuf);
            handler.process(input, outputQueue::addLast);
            input.discardReadBytes();
        }
    }

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
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
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (upstreamUpdater.compareAndSet(this, null, subscription)) {
            deferredInit();
        } else {
            subscription.cancel();
        }
    }

    @Override
    public void onNext(HttpObject data) {
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
            handler.processOnError(ex);
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
    public void onError(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cancelled) {
            return;
        }
        final EventExecutor eventLoop = this.eventLoop;
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> onError(cause));
            return;
        }

        handler.processOnError(cause);
        cleanup();
        abort(cause);
    }

    @Override
    public void onComplete() {
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
        input.close();
    }
}
