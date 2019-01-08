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

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
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
            final HttpResponse response = HttpResponse.of(
                    Flux.concat(Mono.just(headers), publisher.map(factoryWrapper::toHttpData))
                        // Publish the response stream on the event loop in order to avoid the possibility of
                        // calling subscription.request() from multiple threads while publishing messages
                        // with onNext signals or starting the subscription with onSubscribe signal.
                        .publishOn(Schedulers.fromExecutor(ctx.eventLoop())));
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
}
