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

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponse;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A {@link ServerHttpResponse} implementation for the Armeria HTTP server.
 */
final class ArmeriaServerHttpResponse extends AbstractServerHttpResponse {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerHttpResponse.class);

    private final ServiceRequestContext ctx;
    private final CompletableFuture<HttpResponse> future;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    private final ResponseHeadersBuilder armeriaHeaders = ResponseHeaders.builder();

    ArmeriaServerHttpResponse(ServiceRequestContext ctx,
                              CompletableFuture<HttpResponse> future,
                              DataBufferFactoryWrapper<?> factoryWrapper,
                              @Nullable String serverHeader) {
        super(requireNonNull(factoryWrapper, "factoryWrapper").delegate());
        this.ctx = requireNonNull(ctx, "ctx");
        this.future = requireNonNull(future, "future");
        this.factoryWrapper = factoryWrapper;

        if (!Strings.isNullOrEmpty(serverHeader)) {
            armeriaHeaders.set(HttpHeaderNames.SERVER, serverHeader);
        }
    }

    @Override
    public <T> T getNativeResponse() {
        return (T) future;
    }

    private Mono<Void> write(Flux<? extends DataBuffer> publisher) {
        return Mono.deferContextual(contextView -> {
            final HttpResponse response = HttpResponse.of(
                    Flux.concat(Mono.just(armeriaHeaders.build()), publisher.map(factoryWrapper::toHttpData))
                        .contextWrite(contextView)
                        // Publish the response stream on the event loop in order to avoid the possibility of
                        // calling subscription.request() from multiple threads while publishing messages
                        // with onNext signals or starting the subscription with onSubscribe signal.
                        .publishOn(Schedulers.fromExecutor(ctx.eventLoop())));
            future.complete(response);
            return Mono.fromFuture(response.whenComplete())
                       .onErrorResume(cause -> cause instanceof CancelledSubscriptionException ||
                                               cause instanceof AbortedStreamException,
                                      cause -> Mono.empty());
        });
    }

    @Override
    protected Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> body) {
        return write(Flux.from(body));
    }

    @Override
    protected Mono<Void> writeAndFlushWithInternal(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        // Prefetch 1 message because Armeria's HttpResponseSubscriber consumes messages one by one.
        return write(Flux.from(body).concatMap(Flux::from, 1));
    }

    @Override
    protected void applyStatusCode() {
        final HttpStatus httpStatus = getStatusCode();
        if (httpStatus != null) {
            armeriaHeaders.status(httpStatus.value());
        } else {
            // If there is no status code specified, set 200 OK by default.
            armeriaHeaders.status(com.linecorp.armeria.common.HttpStatus.OK);
        }
    }

    @Override
    protected void applyHeaders() {
        getHeaders().forEach((name, values) -> armeriaHeaders.add(HttpHeaderNames.of(name), values));
    }

    @Override
    protected void applyCookies() {
        final List<String> cookieValues =
                getCookies().values().stream()
                            .flatMap(Collection::stream)
                            .map(ArmeriaServerHttpResponse::toSetCookie)
                            .collect(toImmutableList());
        if (!cookieValues.isEmpty()) {
            armeriaHeaders.add(HttpHeaderNames.SET_COOKIE, cookieValues);
        }
    }

    /**
     * Converts the specified {@link ResponseCookie} to Netty's {@link Cookie} interface.
     */
    private static String toSetCookie(ResponseCookie resCookie) {
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
        final Cookie cookie = builder.build();
        return cookie.toSetCookieHeader(false);
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

        final HttpResponse response = HttpResponse.of(armeriaHeaders.build());
        future.complete(response);
        logger.debug("{} Response future has been completed with an HttpResponse", ctx);

        return Mono.fromFuture(response.whenComplete())
                   .onErrorResume(CancelledSubscriptionException.class, e -> Mono.empty());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ctx", ctx)
                          .add("future", future)
                          .add("factoryWrapper", factoryWrapper)
                          .add("headers", armeriaHeaders.build())
                          .toString();
    }
}
