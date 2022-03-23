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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMessage;
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
import com.linecorp.armeria.common.stream.StreamDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} which publishes a stream of objects decoded by {@link HttpDecoder}.
 */
@UnstableApi
public final class DecodedStreamMessage<I, O>
        extends DefaultStreamMessage<O> implements StreamDecoderOutput<O> {

    /**
     * Returns a {@link StreamMessage} that is decoded from the {@link HttpMessage} using the specified
     * {@link HttpDecoder}.
     */
    public static <O> StreamMessage<O> of(HttpMessage httpMessage,
                                          HttpDecoder<O> decoder, ByteBufAllocator alloc) {
        // HttpDecoder only decodes HttpData in HttpMessage.
        @SuppressWarnings("unchecked")
        final StreamDecoder<HttpObject, O> cast = (StreamDecoder<HttpObject, O>) (StreamDecoder<?, ?>) decoder;
        return new DecodedStreamMessage<>(httpMessage, cast, alloc);
    }

    private final DecodingSubscriber subscriber = new DecodingSubscriber();

    private final StreamDecoder<I, O> decoder;
    private final boolean isHttpDecoder;
    private final ByteBufDecoderInput input;
    private final StreamMessage<? extends I> publisher;

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
     * Returns a new {@link DecodedStreamMessage} with the specified {@link HttpDecoder},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    public DecodedStreamMessage(StreamMessage<? extends I> streamMessage,
                                StreamDecoder<I, O> decoder, ByteBufAllocator alloc) {
        publisher = requireNonNull(streamMessage, "streamMessage");
        this.decoder = requireNonNull(decoder, "decoder");
        isHttpDecoder = decoder instanceof HttpDecoder;
        input = new ByteBufDecoderInput(requireNonNull(alloc, "alloc"));
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
    public void add(O out) {
        if (tryWrite(out)) {
            handlerProduced = true;
        } else {
            cancelAndCleanup();
        }
    }

    @Override
    protected void subscribe0(EventExecutor executor, SubscriptionOption[] options) {
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
            //noinspection unchecked
            subscriber.onNext((I) requestHeaders);
            demand--;
        }

        if (demand > 0) {
            askUpstreamForElement();
        }
    }

    @Override
    protected void onRequest(long n) {
        // Fetch from upstream only when this deframer is initialized and the given demand is valid.
        if (initialized && n > 0) {
            if (requestHeaders != null) {
                // A readily available HTTP headers is not delivered yet.
                final HttpHeaders requestHeaders = this.requestHeaders;
                this.requestHeaders = null;
                //noinspection unchecked
                subscriber.onNext((I) requestHeaders);
            } else {
                whenConsumed().thenRun(() -> {
                    if (demand() > 0) {
                        askUpstreamForElement();
                    }
                });
            }
        }
    }

    public void askUpstreamForElement() {
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

    private final class DecodingSubscriber implements Subscriber<I> {

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
        public void onNext(I obj) {
            requireNonNull(obj, "obj");

            askedUpstreamForElement = false;
            handlerProduced = false;
            try {
                // Call the handler so that it publishes something.
                if (isHttpDecoder && obj instanceof HttpHeaders) {
                    // HttpDecoder has dedicated APIs for headers and trailers.
                    final HttpDecoder<O> httpDecoder = (HttpDecoder<O>) decoder;
                    final HttpHeaders headers = (HttpHeaders) obj;
                    if (headers instanceof ResponseHeaders &&
                        ((ResponseHeaders) headers).status().isInformational()) {
                        httpDecoder.processInformationalHeaders((ResponseHeaders) headers,
                                                                DecodedStreamMessage.this);
                    } else if (!sawLeadingHeaders) {
                        sawLeadingHeaders = true;
                        httpDecoder.processHeaders((HttpHeaders) obj, DecodedStreamMessage.this);
                    } else {
                        httpDecoder.processTrailers((HttpHeaders) obj, DecodedStreamMessage.this);
                    }
                } else {
                    final ByteBuf byteBuf = decoder.toByteBuf(obj);
                    requireNonNull(byteBuf, "decoder.toByteBuf() returned null");
                    if (input.add(byteBuf)) {
                        decoder.process(input, DecodedStreamMessage.this);
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
                    // to produce something if there is a demand.
                    if (demand() > 0) {
                        askUpstreamForElement();
                    }
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
                decoder.processOnComplete(input, DecodedStreamMessage.this);
                close();
            } catch (Exception e) {
                abort(e);
            } finally {
                cleanup();
            }
        }
    }
}
