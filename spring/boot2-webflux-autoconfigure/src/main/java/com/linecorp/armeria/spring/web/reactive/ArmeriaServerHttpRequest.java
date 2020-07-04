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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A {@link ServerHttpRequest} implementation for the Armeria HTTP server.
 */
final class ArmeriaServerHttpRequest extends AbstractServerHttpRequest {

    private final ServiceRequestContext ctx;
    private final HttpRequest req;
    private final Flux<DataBuffer> body;

    ArmeriaServerHttpRequest(ServiceRequestContext ctx,
                             HttpRequest req,
                             DataBufferFactoryWrapper<?> factoryWrapper) {
        super(uri(req), null, fromArmeriaHttpHeaders(req.headers()));
        this.ctx = requireNonNull(ctx, "ctx");
        this.req = req;

        body = Flux.from(req).cast(HttpData.class).map(factoryWrapper::toDataBuffer)
                   // Guarantee that the context is accessible from a controller method
                   // when a user specify @RequestBody in order to convert a request body into an object.
                   .publishOn(Schedulers.fromExecutor(ctx.eventLoop()));
    }

    private static URI uri(HttpRequest req) {
        final String scheme = req.scheme();
        final String authority = req.authority();
        // Server side Armeria HTTP request always has the scheme and authority.
        assert scheme != null;
        assert authority != null;
        return URI.create(scheme + "://" + authority + req.path());
    }

    private static HttpHeaders fromArmeriaHttpHeaders(com.linecorp.armeria.common.HttpHeaders httpHeaders) {
        final HttpHeaders newHttpHeaders = new HttpHeaders();
        httpHeaders.forEach(entry -> newHttpHeaders.add(entry.getKey().toString(), entry.getValue()));
        return newHttpHeaders;
    }

    @Override
    protected MultiValueMap<String, HttpCookie> initCookies() {
        final MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        final List<String> values = req.headers().getAll(HttpHeaderNames.COOKIE);
        values.stream()
              .map(ServerCookieDecoder.LAX::decode)
              .flatMap(Collection::stream)
              .forEach(c -> cookies.add(c.name(), new HttpCookie(c.name(), c.value())));
        return cookies;
    }

    @Override
    protected String initId() {
        return ctx.id().text();
    }

    @Nullable
    @Override
    protected SslInfo initSslInfo() {
        final SSLSession session = ctx.sslSession();
        return session != null ? new DefaultSslInfo(session)
                               : null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getNativeRequest() {
        return (T) req;
    }

    @Override
    public String getMethodValue() {
        return req.method().name();
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return body;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return ctx.remoteAddress();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ctx", ctx)
                          .add("req", req)
                          .toString();
    }
}
