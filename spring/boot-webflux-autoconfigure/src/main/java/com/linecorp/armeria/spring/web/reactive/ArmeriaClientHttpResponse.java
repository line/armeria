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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.spring.web.reactive.ArmeriaHttpClientResponseSubscriber.ResponseBodyPublisher;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import reactor.core.publisher.Flux;

/**
 * A {@link ClientHttpResponse} implementation for the Armeria HTTP client.
 */
final class ArmeriaClientHttpResponse implements ClientHttpResponse {

    private final com.linecorp.armeria.common.HttpStatus status;
    private final com.linecorp.armeria.common.HttpHeaders headers;

    private final Flux<DataBuffer> body;

    @Nullable
    private MultiValueMap<String, ResponseCookie> cookies;
    @Nullable
    private HttpHeaders httpHeaders;

    ArmeriaClientHttpResponse(com.linecorp.armeria.common.HttpHeaders headers,
                              ResponseBodyPublisher publisher,
                              ArmeriaBufferFactory factory) {
        this.headers = requireNonNull(headers, "headers");
        final com.linecorp.armeria.common.HttpStatus status = headers.status();
        checkArgument(status != null, "no status in HTTP headers");
        this.status = status;

        body = Flux.from(publisher).cast(HttpData.class).map(factory::wrap);
    }

    @Override
    public HttpStatus getStatusCode() {
        // Raise an IllegalArgumentException if the status code is unknown.
        return HttpStatus.valueOf(status.code());
    }

    @Override
    public int getRawStatusCode() {
        // Return the status code even if it is unknown.
        return status.code();
    }

    @Override
    public MultiValueMap<String, ResponseCookie> getCookies() {
        final MultiValueMap<String, ResponseCookie> cookies = this.cookies;
        if (cookies != null) {
            return cookies;
        }
        this.cookies = initCookies();
        return this.cookies;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return body;
    }

    @Override
    public HttpHeaders getHeaders() {
        final HttpHeaders httpHeaders = this.httpHeaders;
        if (httpHeaders != null) {
            return httpHeaders;
        }
        this.httpHeaders = initHttpHeaders();
        return this.httpHeaders;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("status", status)
                          .add("headers", headers)
                          .toString();
    }

    private MultiValueMap<String, ResponseCookie> initCookies() {
        final MultiValueMap<String, ResponseCookie> cookies = new LinkedMultiValueMap<>();
        headers.getAll(HttpHeaderNames.SET_COOKIE)
               .stream()
               .map(ClientCookieDecoder.LAX::decode)
               .forEach(c -> cookies.add(c.name(), ResponseCookie.from(c.name(), c.value())
                                                                 .maxAge(c.maxAge())
                                                                 .domain(c.domain())
                                                                 .path(c.path())
                                                                 .secure(c.isSecure())
                                                                 .httpOnly(c.isHttpOnly())
                                                                 .build()));
        return cookies;
    }

    private HttpHeaders initHttpHeaders() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(entry -> httpHeaders.add(entry.getKey().toString(), entry.getValue()));
        return httpHeaders;
    }
}
