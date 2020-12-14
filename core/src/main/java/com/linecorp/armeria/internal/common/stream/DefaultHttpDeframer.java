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

import static com.linecorp.armeria.common.stream.SubscriptionOption.NOTIFY_CANCELLATION;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDeframerOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * The default HTTP deframer implementation.
 */
@UnstableApi
public final class DefaultHttpDeframer<T> extends DefaultStreamMessage<T> implements HttpDeframerOutput<T> {

    private static final SubscriptionOption[] EMPTY_OPTIONS = {};
    private static final SubscriptionOption[] POOLED_OBJECTS_OPTIONS = { WITH_POOLED_OBJECTS };
    private static final SubscriptionOption[] NOTIFY_CANCELLATION_OPTIONS = { NOTIFY_CANCELLATION };

    private final HttpMessageSubscriber subscriber = new HttpMessageSubscriber();

    private final HttpDecoder<T> handler;
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
     * Returns a new {@link DefaultHttpDeframer} with the specified {@link HttpDecoder} and
     * {@link ByteBufAllocator}.
     */
    public DefaultHttpDeframer(StreamMessage<? extends HttpObject> streamMessage,
                               HttpDecoder<T> handler, ByteBufAllocator alloc) {
        this(streamMessage, handler, alloc, HttpData::byteBuf);
    }

    /**
     * Returns a new {@link DefaultHttpDeframer} with the specified {@link HttpDecoder},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    public DefaultHttpDeframer(StreamMessage<? extends HttpObject> streamMessage,
                               HttpDecoder<T> handler, ByteBufAllocator alloc,
                               Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        publisher = requireNonNull(streamMessage, "streamMessage");
        this.handler = requireNonNull(handler, "handler");
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
        }
    }

    @Override
    protected void subscribe0(EventExecutor executor, boolean withPooledObjects, boolean notifyCancellation) {
        publisher.subscribe(subscriber, executor, toSubscriptionOptions(withPooledObjects, notifyCancellation));
        deferredInit();
    }

    private void deferredInit() {
        if (upstream != null) {
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
    }

    @Override
    protected void onRequest(long n) {
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

    private static SubscriptionOption[] toSubscriptionOptions(boolean withPooledObjects,
                                                              boolean notifyCancellation) {
        if (withPooledObjects && notifyCancellation) {
            return SubscriptionOption.values();
        }
        if (withPooledObjects) {
            return POOLED_OBJECTS_OPTIONS;
        }
        if (notifyCancellation) {
            return NOTIFY_CANCELLATION_OPTIONS;
        }
        return EMPTY_OPTIONS;
    }

    private final class HttpMessageSubscriber implements Subscriber<HttpObject> {

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream == null) {
                upstream = subscription;
                deferredInit();
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
                        handler.processInformationalHeaders((ResponseHeaders) headers,
                                                            DefaultHttpDeframer.this);
                    } else if (!sawLeadingHeaders) {
                        sawLeadingHeaders = true;
                        handler.processHeaders((HttpHeaders) obj, DefaultHttpDeframer.this);
                    } else {
                        handler.processTrailers((HttpHeaders) obj, DefaultHttpDeframer.this);
                    }
                } else if (obj instanceof HttpData) {
                    final HttpData data = (HttpData) obj;
                    final ByteBuf byteBuf = byteBufConverter.apply(data);
                    requireNonNull(byteBuf, "byteBufConverter.apply() returned null");
                    if (input.add(byteBuf)) {
                        handler.process(input, DefaultHttpDeframer.this);
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
                handler.processOnError(ex);
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
            cleanup();
            close();
        }
    }
}
