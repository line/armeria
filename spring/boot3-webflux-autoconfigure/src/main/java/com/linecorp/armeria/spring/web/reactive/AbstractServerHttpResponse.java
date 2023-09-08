/*
 * Copyright 2023 LINE Corporation
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
/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.spring.web.reactive;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Base class for {@link ServerHttpResponse} implementations.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @author Brian Clozel
 * @since 5.0
 */
abstract class AbstractServerHttpResponse implements ServerHttpResponse {

    // Forked from https://github.com/spring-projects/spring-framework/blob/4beb05ddb327bb533ed410df767e8f787488dfd4/spring-web/src/main/java/org/springframework/http/server/reactive/AbstractServerHttpResponse.java
    // Fixed writeWith() to use `Mono.just()` instead of `Mono.fromCallable()` to release `buffer` when the
    // `Mono` passed into `writeWithInternal()` is canceled.

    /**
     * COMMITTING -> COMMITTED is the period after doCommit is called but before
     * the response status and headers have been applied to the underlying
     * response during which time pre-commit actions can still make changes to
     * the response status and headers.
     */
    enum State {
        NEW, COMMITTING, COMMIT_ACTION_FAILED, COMMITTED
    }

    private final DataBufferFactory dataBufferFactory;

    private final HttpHeaders headers;

    private final MultiValueMap<String, ResponseCookie> cookies;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private final List<Supplier<? extends Mono<Void>>> commitActions = new ArrayList<>(4);

    @Nullable
    private HttpHeaders readOnlyHeaders;

    AbstractServerHttpResponse(DataBufferFactory dataBufferFactory) {
        this(dataBufferFactory, new HttpHeaders());
    }

    AbstractServerHttpResponse(DataBufferFactory dataBufferFactory, HttpHeaders headers) {
        requireNonNull(dataBufferFactory, "DataBufferFactory must not be null");
        requireNonNull(headers, "HttpHeaders must not be null");
        this.dataBufferFactory = dataBufferFactory;
        this.headers = headers;
        cookies = new LinkedMultiValueMap<>();
    }

    @Override
    public final DataBufferFactory bufferFactory() {
        return dataBufferFactory;
    }

    final State state() {
        return state.get();
    }

    @Override
    public HttpHeaders getHeaders() {
        if (readOnlyHeaders != null) {
            return readOnlyHeaders;
        } else if (state.get() == State.COMMITTED) {
            readOnlyHeaders = HttpHeaders.readOnlyHttpHeaders(headers);
            return readOnlyHeaders;
        } else {
            return headers;
        }
    }

    @Override
    public MultiValueMap<String, ResponseCookie> getCookies() {
        return state.get() == State.COMMITTED ? CollectionUtils.unmodifiableMultiValueMap(cookies) : cookies;
    }

    @Override
    public void addCookie(ResponseCookie cookie) {
        requireNonNull(cookie, "ResponseCookie must not be null");

        if (state.get() == State.COMMITTED) {
            throw new IllegalStateException(
                    "Can't add the cookie " + cookie + "because the HTTP response has already been committed");
        } else {
            getCookies().add(cookie.getName(), cookie);
        }
    }

    /**
     * Return the underlying server response.
     *
     * <p><strong>Note:</strong> This is exposed mainly for internal framework
     * use such as WebSocket upgrades in the spring-webflux module.
     */
    public abstract <T> T getNativeResponse();

    @Override
    public void beforeCommit(Supplier<? extends Mono<Void>> action) {
        commitActions.add(action);
    }

    @Override
    public boolean isCommitted() {
        final State state = this.state.get();
        return state != State.NEW && state != State.COMMIT_ACTION_FAILED;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        // For Mono we can avoid ChannelSendOperator and Reactor Netty is more optimized for Mono.
        // We must resolve value first however, for a chance to handle potential error.
        if (body instanceof Mono) {
            return ((Mono<? extends DataBuffer>) body).flatMap(buffer -> {
                touchDataBuffer(buffer);
                final AtomicBoolean subscribed = new AtomicBoolean();
                return doCommit(() -> {
                    try {
                        // Upstream code uses `Mono.fromCallable(() -> buffer)` which does nothing when
                        // `Subscription.cancel()` is called. It can lead to leaking of the buffer when an
                        // HttpResponse is canceled.
                        //
                        return writeWithInternal(Mono.just(buffer)
                                                     .doOnSubscribe(s -> subscribed.set(true))
                                                     .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
                    } catch (Throwable ex) {
                        return Mono.error(ex);
                    }
                }).doOnError(ex -> DataBufferUtils.release(buffer))
                  .doOnCancel(() -> {
                      if (!subscribed.get()) {
                          DataBufferUtils.release(buffer);
                      }
                  });
            }).doOnError(t -> getHeaders().clearContentHeaders()).doOnDiscard(DataBuffer.class,
                                                                              DataBufferUtils::release);
        } else {
            return new ChannelSendOperator<>(body, inner -> doCommit(() -> writeWithInternal(inner)))
                    .doOnError(t -> getHeaders().clearContentHeaders());
        }
    }

    @Override
    public final Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return new ChannelSendOperator<>(body, inner -> doCommit(() -> writeAndFlushWithInternal(inner)))
                .doOnError(t -> getHeaders().clearContentHeaders());
    }

    @Override
    public Mono<Void> setComplete() {
        return !isCommitted() ? doCommit(null) : Mono.empty();
    }

    /**
     * A variant of {@link #doCommit(Supplier)} for a response without a body.
     *
     * @return a completion publisher
     */
    protected Mono<Void> doCommit() {
        return doCommit(null);
    }

    /**
     * Apply {@link #beforeCommit(Supplier) beforeCommit} actions, apply the
     * response status and headers/cookies, and write the response body.
     *
     * @param writeAction the action to write the response body (may be {@code null})
     * @return a completion publisher
     */
    protected Mono<Void> doCommit(@Nullable Supplier<? extends Mono<Void>> writeAction) {
        Flux<Void> allActions = Flux.empty();
        if (state.compareAndSet(State.NEW, State.COMMITTING)) {
            if (!commitActions.isEmpty()) {
                allActions = Flux.concat(Flux.fromIterable(commitActions).map(Supplier::get)).doOnError(ex -> {
                    if (state.compareAndSet(State.COMMITTING, State.COMMIT_ACTION_FAILED)) {
                        getHeaders().clearContentHeaders();
                    }
                });
            }
        } else if (state.compareAndSet(State.COMMIT_ACTION_FAILED, State.COMMITTING)) {
            // Skip commit actions
        } else {
            return Mono.empty();
        }

        allActions = allActions.concatWith(Mono.fromRunnable(() -> {
            applyStatusCode();
            applyHeaders();
            applyCookies();
            state.set(State.COMMITTED);
        }));

        if (writeAction != null) {
            allActions = allActions.concatWith(writeAction.get());
        }

        return allActions.then();
    }

    /**
     * Write to the underlying the response.
     *
     * @param body the publisher to write with
     */
    protected abstract Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> body);

    /**
     * Write to the underlying the response, and flush after each {@code Publisher<DataBuffer>}.
     *
     * @param body the publisher to write and flush with
     */
    protected abstract Mono<Void> writeAndFlushWithInternal(
            Publisher<? extends Publisher<? extends DataBuffer>> body);

    /**
     * Write the status code to the underlying response.
     * This method is called once only.
     */
    protected abstract void applyStatusCode();

    /**
     * Invoked when the response is getting committed allowing subclasses to
     * make apply header values to the underlying response.
     *
     * <p>Note that some subclasses use an {@link HttpHeaders} instance that
     * wraps an adapter to the native response headers such that changes are
     * propagated to the underlying response on the go. That means this callback
     * might not be used other than for specialized updates such as setting
     * the contentType or characterEncoding fields in a Servlet response.
     */
    protected abstract void applyHeaders();

    /**
     * Add cookies from {@link #getHeaders()} to the underlying response.
     * This method is called once only.
     */
    protected abstract void applyCookies();

    /**
     * Allow subclasses to associate a hint with the data buffer if it is a
     * pooled buffer and supports leak tracking.
     *
     * @param buffer the buffer to attach a hint to
     * @since 5.3.2
     */
    protected void touchDataBuffer(DataBuffer buffer) {
    }
}
