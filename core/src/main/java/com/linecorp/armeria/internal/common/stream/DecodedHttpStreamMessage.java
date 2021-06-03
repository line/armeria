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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageAndWriter;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} which publishes a stream of objects decoded by {@link HttpDecoder}.
 */
@UnstableApi
public final class DecodedHttpStreamMessage<T> implements HttpDecoderOutput<T>,
                                                          StreamMessage<T>, StreamWriter<T> {

    private final HttpMessageSubscriber subscriber = new HttpMessageSubscriber();
    private final StreamMessageAndWriter<T> delegate;

    private final HttpDecoder<T> decoder;
    private final ByteBufDecoderInput input;
    private final Function<? super HttpData, ? extends ByteBuf> byteBufConverter;
    private final StreamMessage<? extends HttpObject> publisher;

    @Nullable
    private RequestHeaders requestHeaders;
    @Nullable
    private Subscription upstream;

    private boolean handlerProduced;
    private boolean sawLeadingHeaders;
    private boolean initialized;
    private boolean askedUpstreamForElement;
    private boolean cancelled;

    /**
     * Returns a new {@link DecodedHttpStreamMessage} with the specified {@link HttpDecoder} and
     * {@link ByteBufAllocator}.
     */
    public DecodedHttpStreamMessage(StreamMessage<? extends HttpObject> streamMessage,
                                    HttpDecoder<T> decoder, ByteBufAllocator alloc) {
        this(new DefaultStreamMessage<>(), streamMessage, decoder, alloc, HttpData::byteBuf);
    }

    /**
     * Returns a new {@link DecodedHttpStreamMessage} with the specified {@link HttpDecoder},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    public DecodedHttpStreamMessage(StreamMessage<? extends HttpObject> streamMessage,
                                    HttpDecoder<T> decoder, ByteBufAllocator alloc,
                                    Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        this(new DefaultStreamMessage<>(), streamMessage, decoder, alloc, byteBufConverter);
    }

    /**
     * Returns a new {@link DecodedHttpStreamMessage} with the specified {@link HttpDecoder},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    public DecodedHttpStreamMessage(StreamMessageAndWriter<T> delegate,
                                    StreamMessage<? extends HttpObject> streamMessage,
                                    HttpDecoder<T> decoder, ByteBufAllocator alloc,
                                    Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        this.delegate = delegate;
        delegate.setCallbackListener(this);
        publisher = requireNonNull(streamMessage, "streamMessage");
        this.decoder = requireNonNull(decoder, "decoder");
        input = new ByteBufDecoderInput(requireNonNull(alloc, "alloc"));
        this.byteBufConverter = requireNonNull(byteBufConverter, "byteBufConverter");
        if (publisher instanceof HttpRequest) {
            requestHeaders = ((HttpRequest) publisher).headers();
        }

        whenComplete().handle((unused1, cause) -> {
            if (cause instanceof CancelledSubscriptionException) {
                cancelAndCleanup();
            } else {
                // In addition to 'onComplete()', 'onError()' and 'cancel()',
                // make sure to call 'cleanup()' even when 'abort()' or 'close()' is invoked directly
                cleanup();
            }
            return null;
        });
    }

    @Override
    public void add(T e) {
        if (tryWrite(e)) {
            handlerProduced = true;
        } else {
            cancelAndCleanup();
        }
    }

    @Override
    public void onSubscribe(EventExecutor executor, SubscriptionOption[] options) {
        publisher.subscribe(subscriber, executor, options);
    }

    private void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        if (cancelled) {
            upstream.cancel();
            return;
        }

        long demand = demand();
        if (demand > 0 && requestHeaders != null) {
            final HttpHeaders requestHeaders = this.requestHeaders;
            this.requestHeaders = null;
            subscriber.onNext(requestHeaders);
            demand--;
        }

        if (demand > 0) {
            askUpstreamForElement();
        }
    }

    @Override
    public void onRequest(long n) {
        // Fetch from upstream only when this deframer is initialized and the given demand is valid.
        if (initialized && n > 0) {
            if (requestHeaders != null) {
                // A readily available HTTP headers is not delivered yet.
                final HttpHeaders requestHeaders = this.requestHeaders;
                this.requestHeaders = null;
                subscriber.onNext(requestHeaders);
            } else {
                askUpstreamForElement();
            }
        }
    }

    private void askUpstreamForElement() {
        if (!askedUpstreamForElement) {
            askedUpstreamForElement = true;
            assert upstream != null;
            upstream.request(1);
        }
    }

    private void cancelAndCleanup() {
        if (cancelled) {
            return;
        }

        cancelled = true;
        if (upstream != null) {
            upstream.cancel();
        }
        cleanup();
    }

    private void cleanup() {
        input.close();
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

    private final class HttpMessageSubscriber implements Subscriber<HttpObject> {

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream == null) {
                upstream = subscription;
                initialize();
            } else {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(HttpObject obj) {
            requireNonNull(obj, "obj");

            askedUpstreamForElement = false;
            handlerProduced = false;
            try {
                // Call the handler so that it publishes something.
                if (obj instanceof HttpHeaders) {
                    final HttpHeaders headers = (HttpHeaders) obj;
                    if (headers instanceof ResponseHeaders &&
                        ((ResponseHeaders) headers).status().isInformational()) {
                        decoder.processInformationalHeaders((ResponseHeaders) headers,
                                                            DecodedHttpStreamMessage.this);
                    } else if (!sawLeadingHeaders) {
                        sawLeadingHeaders = true;
                        decoder.processHeaders((HttpHeaders) obj, DecodedHttpStreamMessage.this);
                    } else {
                        decoder.processTrailers((HttpHeaders) obj, DecodedHttpStreamMessage.this);
                    }
                } else if (obj instanceof HttpData) {
                    final HttpData data = (HttpData) obj;
                    final ByteBuf byteBuf = byteBufConverter.apply(data);
                    requireNonNull(byteBuf, "byteBufConverter.apply() returned null");
                    if (input.add(byteBuf)) {
                        decoder.process(input, DecodedHttpStreamMessage.this);
                    }
                }

                if (handlerProduced) {
                    // Handler produced something.
                    if (!askedUpstreamForElement) {
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
                decoder.processOnError(ex);
                cancelAndCleanup();
                abort(ex);
                Exceptions.throwIfFatal(ex);
            }
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (cancelled) {
                return;
            }

            if (!(cause instanceof AbortedStreamException)) {
                decoder.processOnError(cause);
            }

            abort(cause);
            cleanup();
        }

        @Override
        public void onComplete() {
            if (cancelled) {
                return;
            }
            try {
                decoder.processOnComplete(DecodedHttpStreamMessage.this);
                close();
            } catch (Exception e) {
                abort(e);
            } finally {
                cleanup();
            }
        }
    }
}
