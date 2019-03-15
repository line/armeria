/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ChannelSendOperator;
import org.springframework.http.server.reactive.ServerHttpResponse;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link ServerHttpResponse} implementation for the Armeria HTTP server.
 */
final class ArmeriaServerHttpResponse extends AbstractServerHttpResponse {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerHttpResponse.class);

    private final ServiceRequestContext ctx;
    private final CompletableFuture<HttpResponse> future;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    private final HttpHeaders headers;

    ArmeriaServerHttpResponse(ServiceRequestContext ctx,
                              CompletableFuture<HttpResponse> future,
                              DataBufferFactoryWrapper<?> factoryWrapper,
                              @Nullable String serverHeader) {
        super(requireNonNull(factoryWrapper, "factoryWrapper").delegate());
        this.ctx = requireNonNull(ctx, "ctx");
        this.future = requireNonNull(future, "future");
        this.factoryWrapper = factoryWrapper;

        headers = new DefaultHttpHeaders();
        if (!Strings.isNullOrEmpty(serverHeader)) {
            headers.set(HttpHeaderNames.SERVER, serverHeader);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getNativeResponse() {
        return (T) future;
    }

    @Override
    protected Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> publisher) {
        return write(Flux.from(publisher));
    }

    @Override
    protected Mono<Void> writeAndFlushWithInternal(
            Publisher<? extends Publisher<? extends DataBuffer>> publisher) {
        // Prefetch 1 message because Armeria's HttpResponseSubscriber consumes messages one by one.
        return write(Flux.from(publisher).concatMap(Flux::from, 1));
    }

    private Mono<Void> write(Flux<? extends DataBuffer> publisher) {
        return Mono.defer(() -> {
            final HttpResponse response = HttpResponse.of(new HttpResponseProcessor(
                    ctx.eventLoop(), headers, publisher.map(factoryWrapper::toHttpData)));
            future.complete(response);
            return Mono.fromFuture(response.completionFuture());
        });
    }

    @Override
    protected void applyStatusCode() {
        final HttpStatus httpStatus = getStatusCode();
        if (httpStatus != null) {
            headers.status(httpStatus.value());
        } else {
            // If there is no status code specified, set 200 OK by default.
            headers.status(com.linecorp.armeria.common.HttpStatus.OK);
        }
    }

    @Override
    protected void applyHeaders() {
        getHeaders().forEach((name, values) -> headers.add(HttpHeaderNames.of(name), values));
    }

    @Override
    protected void applyCookies() {
        final List<String> cookieValues =
                getCookies().values().stream()
                            .flatMap(Collection::stream)
                            .map(ArmeriaServerHttpResponse::toNettyCookie)
                            .map(ServerCookieEncoder.LAX::encode)
                            .collect(toImmutableList());
        if (!cookieValues.isEmpty()) {
            headers.add(HttpHeaderNames.SET_COOKIE, cookieValues);
        }
    }

    @Override
    public Mono<Void> setComplete() {
        return setComplete(null);
    }

    /**
     * Closes the {@link HttpResponseWriter} with the specified {@link Throwable} which is raised during
     * sending the response.
     */
    public Mono<Void> setComplete(@Nullable Throwable cause) {
        return super.setComplete().then(Mono.defer(() -> cleanup(cause)));
    }

    /**
     * Closes the {@link HttpResponseWriter} if it is opened.
     */
    private Mono<Void> cleanup(@Nullable Throwable cause) {
        if (future.isDone()) {
            return Mono.empty();
        }

        if (cause != null) {
            future.completeExceptionally(cause);
            logger.debug("{} Response future has been completed with a cause", ctx, cause);
            return Mono.empty();
        }

        final HttpResponse response = HttpResponse.of(headers);
        future.complete(response);
        logger.debug("{} Response future has been completed with an HttpResponse", ctx);
        return Mono.fromFuture(response.completionFuture());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ctx", ctx)
                          .add("future", future)
                          .add("factoryWrapper", factoryWrapper)
                          .add("headers", headers)
                          .toString();
    }

    /**
     * Converts the specified {@link ResponseCookie} to Netty's {@link Cookie} interface.
     */
    static Cookie toNettyCookie(ResponseCookie resCookie) {
        final DefaultCookie cookie = new DefaultCookie(resCookie.getName(), resCookie.getValue());
        cookie.setHttpOnly(resCookie.isHttpOnly());
        cookie.setMaxAge(resCookie.getMaxAge().getSeconds());
        cookie.setSecure(resCookie.isSecure());
        // Domain and path are nullable, but the setters allow null as their input.
        cookie.setDomain(resCookie.getDomain());
        cookie.setPath(resCookie.getPath());
        return cookie;
    }

    /**
     * The {@link ChannelSendOperator.WriteBarrier} caches the first published object but it does nothing
     * when it is discarded, which might cause {@link ByteBuf} leak. This processor behaves similarly
     * but will release the cached object when the subscription is completed.
     */
    private static final class HttpResponseProcessor implements Processor<HttpData, HttpObject> {

        private enum State {
            INIT, HEADER_SENT, FIRST_CONTENT_SENT
        }

        private final EventLoop eventLoop;
        private final HttpHeaders headers;
        private final Flux<HttpData> upstream;

        private State state = State.INIT;
        private boolean onCompleteSignalReceived;

        @Nullable
        private Subscriber<? super HttpObject> subscriber;
        @Nullable
        private HttpObject firstContent;
        @Nullable
        private Subscription upstreamSubscription;

        HttpResponseProcessor(EventLoop eventLoop, HttpHeaders headers, Flux<HttpData> upstream) {
            this.eventLoop = eventLoop;
            this.headers = headers;
            this.upstream = upstream;
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> subscriber) {
            this.subscriber = requireNonNull(subscriber, "subscriber");
            upstream.subscribe(this);
        }

        private Subscriber<? super HttpObject> subscriber() {
            assert subscriber != null : "Subscriber is null.";
            return subscriber;
        }

        @Override
        public void onSubscribe(Subscription s) {
            assert upstreamSubscription == null;
            upstreamSubscription = s;
            // To get the cached object from the upstream which is ChannelSendOperator#WriteBarrier,
            // request 1 object to the publisher before the subscription is finished.
            s.request(1);
        }

        @Override
        public void onNext(HttpData o) {
            if (eventLoop.inEventLoop()) {
                onNext0(o);
            } else {
                eventLoop.execute(() -> onNext0(o));
            }
        }

        private void onNext0(HttpObject o) {
            if (firstContent == null) {
                firstContent = o;
                assert upstreamSubscription != null;
                finishSubscribing(upstreamSubscription);
            } else {
                // We don't request more demands to the upstream publisher until the first cached content
                // is published to the subscriber.
                assert state == State.FIRST_CONTENT_SENT;
                subscriber().onNext(o);
            }
        }

        private void finishSubscribing(Subscription s) {
            assert firstContent != null;
            subscriber().onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    if (!isValidDemand(n)) {
                        cancel();
                        return;
                    }
                    if (eventLoop.inEventLoop()) {
                        request0(n);
                    } else {
                        eventLoop.execute(() -> request0(n));
                    }
                }

                private void request0(long n) {
                    boolean more = true;
                    while (more) {
                        switch (state) {
                            case INIT:
                                state = State.HEADER_SENT;
                                subscriber().onNext(headers);
                                more = --n > 0;
                                break;
                            case HEADER_SENT:
                                state = State.FIRST_CONTENT_SENT;
                                subscriber().onNext(firstContent);
                                more = --n > 0;
                                break;
                            case FIRST_CONTENT_SENT:
                                // If we already received onComplete signal, do not request more demands.
                                if (onCompleteSignalReceived) {
                                    subscriber().onComplete();
                                } else {
                                    s.request(n);
                                }
                                more = false;
                                break;
                        }
                    }
                }

                @Override
                public void cancel() {
                    releaseFirstContent();
                    s.cancel();
                }
            });
        }

        @Override
        public void onError(Throwable cause) {
            if (eventLoop.inEventLoop()) {
                onError0(cause);
            } else {
                eventLoop.execute(() -> onError0(cause));
            }
        }

        private void onError0(Throwable cause) {
            if (firstContent == null) {
                finishSubscribingWithCompletedUpstream(cause);
            } else {
                releaseFirstContent();
                subscriber().onError(cause);
            }
        }

        @Override
        public void onComplete() {
            if (eventLoop.inEventLoop()) {
                onComplete0();
            } else {
                eventLoop.execute(this::onComplete0);
            }
        }

        private void onComplete0() {
            if (firstContent == null) {
                // onComplete can be invoked immediately in response to the first request if the upstream
                // publisher has no element to emit.
                finishSubscribingWithCompletedUpstream(null);
            } else if (state == State.FIRST_CONTENT_SENT) {
                // onComplete can be invoked immediately after onNext is invoked, if the upstream publisher
                // has only one element to emit. In this case, the headers and the first cached content
                // might not be published to the subscriber. So we keep the onComplete signal and send it later
                // when the subscriber requests more demands.
                subscriber().onComplete();
            } else {
                onCompleteSignalReceived = true;
            }
        }

        private void finishSubscribingWithCompletedUpstream(@Nullable Throwable cause) {
            subscriber().onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    if (!isValidDemand(n)) {
                        return;
                    }
                    if (eventLoop.inEventLoop()) {
                        request0(n);
                    } else {
                        eventLoop.execute(() -> request0(n));
                    }
                }

                private void request0(long n) {
                    if (state == State.INIT) {
                        state = State.HEADER_SENT;
                        subscriber().onNext(headers);
                        n--;
                    }
                    if (n > 0) {
                        assert firstContent == null;
                        if (cause == null) {
                            subscriber().onComplete();
                        } else {
                            subscriber().onError(cause);
                        }
                    }
                }

                @Override
                public void cancel() {
                    // Do nothing because the upstream has already been completed.
                }
            });
        }

        private void releaseFirstContent() {
            if (state != State.FIRST_CONTENT_SENT &&
                firstContent instanceof ReferenceCounted) {
                logger.debug("Releasing the first cached content: {}", firstContent);
                ReferenceCountUtil.safeRelease(firstContent);
            }
        }

        private boolean isValidDemand(long n) {
            if (n > 0) {
                return true;
            }

            final IllegalArgumentException iae = new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)");
            if (eventLoop.inEventLoop()) {
                subscriber().onError(iae);
            } else {
                eventLoop.execute(() -> subscriber().onError(iae));
            }
            return false;
        }
    }
}
