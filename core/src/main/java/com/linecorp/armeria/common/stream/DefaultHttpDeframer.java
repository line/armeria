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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * The default {@link HttpDeframer} implementation.
 */
final class DefaultHttpDeframer<T>
        extends DefaultStreamMessage<T>
        implements HttpDeframer<T>, HttpDeframerOutput<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHttpDeframer, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpDeframer.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DefaultHttpDeframer> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpDeframer.class, "initialized");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DefaultHttpDeframer> askedUpstreamForElementUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpDeframer.class, "askedUpstreamForElement");

    private final HttpDeframerHandler<T> handler;
    private final ByteBufDeframerInput input;
    private final Function<? super HttpData, ? extends ByteBuf> byteBufConverter;

    private boolean handlerProduced;
    private boolean sawLeadingHeaders;

    @Nullable
    private volatile EventExecutor eventLoop;
    @Nullable
    private volatile Subscription upstream;
    private volatile int initialized;
    private volatile int askedUpstreamForElement;

    @Nullable
    private volatile Throwable cause;
    private volatile boolean cancelled;
    private volatile boolean completing;

    /**
     * Returns a new {@link DefaultHttpDeframer} with the specified {@link HttpDeframerHandler},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    DefaultHttpDeframer(HttpDeframerHandler<T> handler, ByteBufAllocator alloc,
                        Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        this.handler = requireNonNull(handler, "handler");
        input = new ByteBufDeframerInput(requireNonNull(alloc, "alloc"));
        this.byteBufConverter = requireNonNull(byteBufConverter, "byteBufConverter");

        whenComplete().handle((unused1, unused2)  -> {
            // In addition to 'onComplete()', 'onError()' and 'cancel()',
            // make sure to call 'cleanup()' even when 'abort()' or 'close()' is invoked directly
            cleanup();
            return null;
        });
    }

    @Override
    public void add(T e) {
        if (tryWrite(e)) {
            handlerProduced = true;
        }
    }

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        final SubscriptionImpl subscriptionImpl = super.subscribe(subscription);
        if (subscriptionImpl == subscription) {
            final EventExecutor eventLoop = subscription.executor();
            this.eventLoop = eventLoop;
            deferredInit(eventLoop);
        }
        return subscriptionImpl;
    }

    private void deferredInit(@Nullable EventExecutor eventLoop) {
        final Subscription upstream = this.upstream;

        if (upstream != null && eventLoop != null) {
            if (initializedUpdater.compareAndSet(this, 0, 1)) {
                if (cancelled) {
                    upstream.cancel();
                    return;
                }

                final Throwable cause = this.cause;
                if (cause != null) {
                    if (eventLoop.inEventLoop()) {
                        onError0(cause);
                    } else {
                        eventLoop.execute(() -> onError0(cause));
                    }
                    return;
                }

                if (completing) {
                    if (eventLoop.inEventLoop()) {
                        onComplete0();
                    } else {
                        eventLoop.execute(this::onComplete0);
                    }
                    return;
                }

                if (demand() > 0) {
                    askUpstreamForElement();
                }
            }
        }
    }

    @Override
    void request(long n) {
        // Fetch from upstream only when this deframer is initialized and the given demand is valid.
        if (initialized != 0 && n > 0) {
            askUpstreamForElement();
        }

        super.request(n);
    }

    private void askUpstreamForElement() {
        if (askedUpstreamForElementUpdater.compareAndSet(this, 0, 1)) {
            final Subscription upstream = this.upstream;
            assert upstream != null;
            upstream.request(1);
        }
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
            deferredInit(eventLoop);
        } else {
            subscription.cancel();
        }
    }

    @Override
    public void onNext(HttpObject data) {
        final EventExecutor eventLoop = this.eventLoop;
        assert eventLoop != null;
        if (eventLoop.inEventLoop()) {
            onNext0(data);
        } else {
            eventLoop.execute(() -> onNext0(data));
        }
    }

    private void onNext0(HttpObject obj) {
        askedUpstreamForElement = 0;
        handlerProduced = false;
        try {
            // Call the handler so that it publishes something.
            if (obj instanceof HttpHeaders) {
                final HttpHeaders headers = (HttpHeaders) obj;
                if (headers instanceof ResponseHeaders &&
                    ((ResponseHeaders) headers).status().isInformational()) {
                    handler.processInformationalHeaders((ResponseHeaders) headers, this);
                } else if (!sawLeadingHeaders) {
                    sawLeadingHeaders = true;
                    handler.processHeaders((HttpHeaders) obj, this);
                } else {
                    handler.processTrailers((HttpHeaders) obj, this);
                }
            } else if (obj instanceof HttpData) {
                final HttpData data = (HttpData) obj;
                final ByteBuf byteBuf = byteBufConverter.apply((HttpData) data);
                requireNonNull(byteBuf, "byteBufConverter.apply() returned null");
                if (input.add(byteBuf)) {
                    handler.process(input, this);
                }
            }

            if (handlerProduced) {
                // Handler produced something.
                if (askedUpstreamForElement == 0) {
                    // Ask the upstream for more elements after the produced elements are fully consumed and
                    // there are still demands left.
                    whenConsumed().handle((unused1, unused2) -> {
                        if (demand() > 0) {
                            askUpstreamForElement();
                        }
                        return null;
                    });
                } else {
                    // No need to ask the upstream for more elements because:
                    // - The handler triggered Subscription.request(); or
                    // - Subscription.request() was called from another thread.
                }
            } else {
                // Handler didn't produce anything, which means it needs more elements from the upstream
                // to produce something.
                askUpstreamForElement();
            }
        } catch (Throwable ex) {
            handler.processOnError(ex);
            cancelAndCleanup();
            abort(ex);
            Exceptions.throwIfFatal(ex);
        }
    }

    private void cancelAndCleanup() {
        if (cancelled) {
            return;
        }

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

        this.cause = cause;
        final EventExecutor eventLoop = this.eventLoop;
        if (eventLoop != null) {
            if (eventLoop.inEventLoop()) {
                onError0(cause);
            } else {
                eventLoop.execute(() -> onError0(cause));
            }
        }
    }

    private void onError0(Throwable cause) {
        if (!(cause instanceof AbortedStreamException)) {
            handler.processOnError(cause);
        }

        abort(cause);
        cleanup();
    }

    @Override
    public void onComplete() {
        if (cancelled) {
            return;
        }

        completing = true;
        final EventExecutor eventLoop = this.eventLoop;
        if (eventLoop != null) {
            if (eventLoop.inEventLoop()) {
                onComplete0();
            } else {
                eventLoop.execute(this::onComplete0);
            }
        }
    }

    private void onComplete0() {
        cleanup();
        close();
    }

    private void cleanup() {
        input.close();
    }
}
