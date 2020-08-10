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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link ServerHttpResponse} implementation for the Armeria HTTP server.
 */
final class ArmeriaServerHttpResponse implements ServerHttpResponse {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerHttpResponse.class);

    private enum State {
        /**
         * The initial state.
         */
        NEW,
        /**
         * Started to prepare an {@link ResponseHeaders} to be emitted.
         */
        COMMITTING,
        /**
         * The {@link ResponseHeaders} has been ready and not allowed for modification anymore.
         */
        COMMITTED
    }

    private static final AtomicReferenceFieldUpdater<ArmeriaServerHttpResponse, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(ArmeriaServerHttpResponse.class, State.class, "state");

    private volatile State state = State.NEW;

    private final HttpHeaders headers = new HttpHeaders();
    private final MultiValueMap<String, ResponseCookie> cookies = new LinkedMultiValueMap<>();
    private final List<Supplier<? extends Mono<Void>>> commitActions = new ArrayList<>(4);

    private final ServiceRequestContext ctx;
    private final CompletableFuture<HttpResponse> future;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    private final ResponseHeadersBuilder armeriaHeaders = ResponseHeaders.builder();

    ArmeriaServerHttpResponse(ServiceRequestContext ctx,
                              CompletableFuture<HttpResponse> future,
                              DataBufferFactoryWrapper<?> factoryWrapper,
                              @Nullable String serverHeader) {
        this.ctx = requireNonNull(ctx, "ctx");
        this.future = requireNonNull(future, "future");
        this.factoryWrapper = factoryWrapper;

        if (!Strings.isNullOrEmpty(serverHeader)) {
            armeriaHeaders.set(HttpHeaderNames.SERVER, serverHeader);
        }
    }

    @Override
    public boolean setStatusCode(@Nullable HttpStatus status) {
        if (state == State.COMMITTED) {
            return false;
        }

        if (status != null) {
            armeriaHeaders.status(status.value());
        }
        return true;
    }

    @Nullable
    @Override
    public HttpStatus getStatusCode() {
        final String statusCode = armeriaHeaders.get(HttpHeaderNames.STATUS);
        if (statusCode == null) {
            return null;
        }
        return HttpStatus.resolve(com.linecorp.armeria.common.HttpStatus.valueOf(statusCode).code());
    }

    @Override
    public boolean setRawStatusCode(@Nullable Integer statusCode) {
        if (state == State.COMMITTED) {
            return false;
        }

        if (statusCode != null) {
            armeriaHeaders.status(statusCode);
        }
        return true;
    }

    @Nullable
    @Override
    public Integer getRawStatusCode() {
        final String statusCode = armeriaHeaders.get(HttpHeaderNames.STATUS);
        if (statusCode == null) {
            return null;
        }
        return com.linecorp.armeria.common.HttpStatus.valueOf(statusCode).code();
    }

    @Override
    public MultiValueMap<String, ResponseCookie> getCookies() {
        return state == State.COMMITTED ? CollectionUtils.unmodifiableMultiValueMap(cookies)
                                        : cookies;
    }

    @Override
    public void addCookie(ResponseCookie cookie) {
        requireNonNull(cookie, "cookie");
        checkState(state != State.COMMITTED,
                   "Can't add the cookie %s because the HTTP response has already been committed",
                   cookie);
        getCookies().add(cookie.getName(), cookie);
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return factoryWrapper.delegate();
    }

    @Override
    public void beforeCommit(Supplier<? extends Mono<Void>> action) {
        commitActions.add(action);
    }

    @Override
    public boolean isCommitted() {
        return state != State.NEW;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return doCommit(() -> write(Flux.from(body)));
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        // Prefetch 1 message because Armeria's HttpRequestSubscriber consumes messages one by one.
        return write(Flux.from(body).concatMap(Flux::from, 1));
    }

    private Mono<Void> write(Flux<? extends DataBuffer> publisher) {
        return Mono.defer(() -> {
            final HttpResponse response = HttpResponse.of(
                    new HttpResponseProcessor(ctx.eventLoop(), buildResponseHeaders(),
                                              publisher.map(factoryWrapper::toHttpData)));
            future.complete(response);
            return Mono.fromFuture(response.whenComplete())
                       .onErrorResume(cause -> cause instanceof CancelledSubscriptionException ||
                                               cause instanceof AbortedStreamException,
                                      cause -> Mono.empty());
        });
    }

    private ResponseHeaders buildResponseHeaders() {
        if (!armeriaHeaders.contains(HttpHeaderNames.STATUS)) {
            // If there is no status code specified, set 200 OK by default.
            armeriaHeaders.status(com.linecorp.armeria.common.HttpStatus.OK);
        }
        return armeriaHeaders.build();
    }

    private Mono<Void> doCommit(@Nullable Supplier<? extends Mono<Void>> writeAction) {
        if (!stateUpdater.compareAndSet(this, State.NEW, State.COMMITTING)) {
            return Mono.empty();
        }

        commitActions.add(() -> Mono.fromRunnable(() -> {
            getHeaders().forEach((name, values) -> armeriaHeaders.add(HttpHeaderNames.of(name), values));

            final List<String> cookieValues =
                    getCookies().values().stream()
                                .flatMap(Collection::stream)
                                .map(ArmeriaServerHttpResponse::toArmeriaCookie)
                                .map(c -> c.toSetCookieHeader(false))
                                .collect(toImmutableList());
            if (!cookieValues.isEmpty()) {
                armeriaHeaders.add(HttpHeaderNames.SET_COOKIE, cookieValues);
            }

            state = State.COMMITTED;
        }));

        if (writeAction != null) {
            commitActions.add(writeAction);
        }

        final List<? extends Mono<Void>> actions =
                commitActions.stream().map(Supplier::get).collect(Collectors.toList());
        return Flux.concat(actions).then();
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
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
        return !isCommitted() ? doCommit(null).then(Mono.defer(() -> cleanup(cause)))
                              : Mono.defer(() -> cleanup(cause));
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

        final HttpResponse response = HttpResponse.of(buildResponseHeaders());
        future.complete(response);
        logger.debug("{} Response future has been completed with an HttpResponse", ctx);

        return Mono.fromFuture(response.whenComplete())
                   .onErrorResume(CancelledSubscriptionException.class, e -> Mono.empty());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ctx", ctx)
                          .add("state", state)
                          .add("future", future)
                          .add("factoryWrapper", factoryWrapper)
                          .add("headers", headers)
                          .add("cookies", cookies)
                          .toString();
    }

    /**
     * Converts the specified {@link ResponseCookie} to Netty's {@link Cookie} interface.
     */
    private static Cookie toArmeriaCookie(ResponseCookie resCookie) {
        final CookieBuilder builder = Cookie.builder(resCookie.getName(), resCookie.getValue());
        if (!resCookie.getMaxAge().isNegative()) {
            builder.maxAge(resCookie.getMaxAge().getSeconds());
        }
        if (resCookie.getDomain() != null) {
            builder.domain(resCookie.getDomain());
        }
        if (resCookie.getPath() != null) {
            builder.path(resCookie.getPath());
        }
        builder.secure(resCookie.isSecure());
        builder.httpOnly(resCookie.isHttpOnly());
        if (resCookie.getSameSite() != null) {
            builder.sameSite(resCookie.getSameSite());
        }
        return builder.build();
    }

    /**
     * When a controller returns a {@link Mono}, it may internally prepare an object to be published but
     * the object will not be consumed by a {@code doOnDiscard} hook when the subscription is cancelled.
     * So we cannot release when a {@link NettyDataBuffer} is prepared and discarded. This processor
     * always consumes one element from the publisher and holds its reference so that it can be released
     * when the subscription is cancelled.
     */
    private static final class HttpResponseProcessor implements Processor<HttpData, HttpObject> {

        private enum PublishingState {
            INIT, HEADER_SENT, FIRST_CONTENT_SENT
        }

        private final EventLoop eventLoop;
        private final com.linecorp.armeria.common.HttpHeaders headers;
        private final Flux<HttpData> upstream;

        private PublishingState state = PublishingState.INIT;
        private boolean onCompleteSignalReceived;

        @Nullable
        private Subscriber<? super HttpObject> subscriber;
        @Nullable
        private HttpObject firstContent;
        @Nullable
        private Subscription upstreamSubscription;

        HttpResponseProcessor(EventLoop eventLoop,
                              com.linecorp.armeria.common.HttpHeaders headers,
                              Flux<HttpData> upstream) {
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
                assert state == PublishingState.FIRST_CONTENT_SENT;
                subscriber().onNext(o);
            }
        }

        private void finishSubscribing(Subscription s) {
            assert firstContent != null;
            subscriber().onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    if (!validateDemand(n)) {
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
                    do {
                        switch (state) {
                            case INIT:
                                state = PublishingState.HEADER_SENT;
                                subscriber().onNext(headers);
                                n--;
                                break;
                            case HEADER_SENT:
                                state = PublishingState.FIRST_CONTENT_SENT;
                                subscriber().onNext(firstContent);
                                n--;
                                break;
                            case FIRST_CONTENT_SENT:
                                // If we already received onComplete signal, do not request more demands.
                                if (onCompleteSignalReceived) {
                                    subscriber().onComplete();
                                } else {
                                    s.request(n);
                                }
                                return;
                        }
                    } while (n > 0);
                }

                @Override
                public void cancel() {
                    s.cancel();
                    if (eventLoop.inEventLoop()) {
                        releaseFirstContent();
                    } else {
                        eventLoop.execute(HttpResponseProcessor.this::releaseFirstContent);
                    }
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
            } else if (state == PublishingState.FIRST_CONTENT_SENT) {
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
                    if (!validateDemand(n)) {
                        return;
                    }
                    if (eventLoop.inEventLoop()) {
                        request0(n);
                    } else {
                        eventLoop.execute(() -> request0(n));
                    }
                }

                private void request0(long n) {
                    if (state == PublishingState.INIT) {
                        state = PublishingState.HEADER_SENT;
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
            if (state != PublishingState.FIRST_CONTENT_SENT &&
                firstContent instanceof HttpData) {
                logger.debug("Releasing the first cached content: {}", firstContent);
                ((HttpData) firstContent).close();
            }
        }

        private boolean validateDemand(long n) {
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
