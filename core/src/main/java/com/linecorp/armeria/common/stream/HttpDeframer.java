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

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link Processor} implementation that decodes a stream of {@link HttpObject}s to N objects.
 *
 * <p>Follow the below steps to deframe HTTP payload using {@link HttpDeframer}.
 * <ol>
 *   <li>Implement your deframing logic in {@link HttpDeframerHandler}.
 *       <pre>{@code
 *       > class FixedLengthDecoder implements HttpDeframerHandler<String> {
 *       >     private final int length;
 *
 *       >     FixedLengthDecoder(int length) {
 *       >         this.length = length;
 *       >     }
 *
 *       >     @Override
 *       >     public void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
 *       >         int remaining = in.readableBytes();
 *       >         if (remaining < length) {
 *       >             // The input is not enough to process. Waiting for more data.
 *       >             return;
 *       >         }
 *
 *       >         do {
 *       >             // Read data from 'HttpDeframerInput' and
 *       >             // write the processed result to 'HttpDeframerOutput'.
 *       >             ByteBuf buf = in.readBytes(length);
 *       >             out.add(buf.toString(StandardCharsets.UTF_8));
 *       >             // Should release the returned 'ByteBuf'
 *       >             buf.release();
 *       >             remaining -= length;
 *       >         } while (remaining >= length);
 *       >     }
 *       > }
 *       }</pre>
 *   </li>
 *   <li>Create an {@link HttpDeframer} with the {@link HttpDeframerHandler} instance.
 *       <pre>{@code
 *       FixedLengthDecoder decoder = new FixedLengthDecoder(11);
 *       HttpDeframer<String> deframer = new HttpDeframer<>(decoder, ByteBufAllocator.DEFAULT);
 *       }</pre>
 *   </li>
 *   <li>Subscribe to an {@link HttpRequest} using the {@link HttpDeframer}.
 *       <pre>{@code
 *       HttpRequest request = ...;
 *       request.subscribe(deframer);
 *       }</pre>
 *   </li>
 *   <li>Subscribe to the {@link Publisher} of the deframed data and connect to your business logic.
 *       <pre>{@code
 *       import reactor.core.publisher.Flux;
 *       Flux.from(deframer).map(...); // Consume and manipulate the deframed data.
 *       }</pre>
 *   </li>
 * </ol>
 */
@UnstableApi
public final class HttpDeframer<T> extends DefaultStreamMessage<T> implements Processor<HttpObject, T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpDeframer, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpDeframer.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpDeframer> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpDeframer.class, "initialized");

    private final HttpDeframerHandler<T> handler;
    private final ByteBufDeframerInput input;
    private final Function<? super HttpData, ? extends ByteBuf> byteBufConverter;

    private boolean sawLeadingHeaders;

    @Nullable
    private volatile EventExecutor eventLoop;
    @Nullable
    private volatile Subscription upstream;
    @Nullable
    private volatile Throwable cause;
    private volatile boolean cancelled;
    private volatile boolean completing;
    private volatile int initialized;

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
        this.byteBufConverter = requireNonNull(byteBufConverter, "byteBufConverter");

        whenComplete().handle((unused1, unused2)  -> {
            // In addition to 'onComplete()', 'onError()' and 'cancel()',
            // make sure to call 'cleanup()' even when 'abort()' or 'close()' is invoked directly
            cleanup();
            return null;
        });
    }

    private void process(HttpObject data) throws Exception {
        if (data instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) data;

            if (headers instanceof ResponseHeaders) {
                final ResponseHeaders responseHeaders = (ResponseHeaders) headers;
                if (responseHeaders.status().isInformational()) {
                    handler.processInformationalHeaders(responseHeaders, this::write);
                    return;
                }
            }

            if (!sawLeadingHeaders) {
                sawLeadingHeaders = true;
                handler.processHeaders((HttpHeaders) data, this::write);
            } else {
                handler.processTrailers((HttpHeaders) data, this::write);
            }
            return;
        }

        if (data instanceof HttpData) {
            final ByteBuf byteBuf = byteBufConverter.apply((HttpData) data);
            requireNonNull(byteBuf, "byteBufConverter.apply() returned null");
            input.add(byteBuf);
            handler.process(input, this::write);
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
                    // The 'demand' will be decreased by 'DefaultStreamMessage.notifySubscriberWithElements()'
                    upstream.request(1);
                }
            }
        }
    }

    @Override
    void request(long n) {
        if (initialized != 0 && demand() == 0) {
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
            deferredInit(eventLoop);
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

            whenConsumed().thenRun(() -> {
                if (demand() > 0) {
                    // The `demand` will be decreased by 'DefaultStreamMessage.notifySubscriberWithElements()'
                    upstream.request(1);
                }
            });
        } catch (Throwable ex) {
            handler.processOnError(ex);
            cancelAndCleanup();
            abort(ex);
            Exceptions.throwIfFatal(ex);
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
