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

package com.linecorp.armeria.micrometer.tracing.client;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;

import io.netty.util.AsciiString;

/**
 * Wraps a pair of {@link ClientRequestContext} and {@link RequestHeadersBuilder} in an {@link
 * io.micrometer.tracing.http.HttpClientRequest}.
 */
final class ClientRequestContextAdapter {
    static HttpClientRequest asHttpClientRequest(ClientRequestContext ctx,
        RequestHeadersBuilder headersBuilder) {
        return new HttpClientRequest(ctx, headersBuilder);
    }

    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpClientRequest implements io.micrometer.tracing.http.HttpClientRequest {
        private final ClientRequestContext ctx;
        private final RequestHeadersBuilder headersBuilder;

        HttpClientRequest(ClientRequestContext ctx, RequestHeadersBuilder headersBuilder) {
            this.ctx = ctx;
            this.headersBuilder = headersBuilder;
        }

        @Override
        public Collection<String> headerNames() {
            final HttpRequest req = ctx.request();
            return req != null ? req.headers().names().stream().map(AsciiString::toString).collect(
                    Collectors.toSet()) : Collections.emptySet();
        }

        @Override
        public ClientRequestContext unwrap() {
            return ctx;
        }

        @Override
        public String method() {
            return ctx.method().name();
        }

        @Override
        public String path() {
            return ctx.path();
        }

        @Override
        public String url() {
            return ctx.uri().toString();
        }

        @Override
        @Nullable
        public String header(String name) {
            final HttpRequest req = ctx.request();
            return req != null ? req.headers().get(name) : null;
        }

        @Override
        public void header(String name, String value) {
            headersBuilder.set(name, value);
        }
    }

    static io.micrometer.tracing.http.HttpClientResponse asHttpClientResponse(RequestLog log,
        io.micrometer.tracing.http.HttpClientRequest request) {
        return new HttpClientResponse(log, request);
    }

    /**
     * Note that this class is used only after {@link RequestLog} is complete.
     */
    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpClientResponse implements io.micrometer.tracing.http.HttpClientResponse {
        private final RequestLog log;
        private final io.micrometer.tracing.http.HttpClientRequest request;

        HttpClientResponse(RequestLog log, io.micrometer.tracing.http.HttpClientRequest request) {
            assert log.isComplete() : log;
            this.log = log;
            this.request = request;
        }

        @Override
        public ClientRequestContext unwrap() {
            return (ClientRequestContext) log.context();
        }

        @Override
        public Collection<String> headerNames() {
            return null;
        }

        @Override
        public io.micrometer.tracing.http.HttpClientRequest request() {
            return request;
        }

        @Override
        @Nullable
        public Throwable error() {
            return log.responseCause();
        }

        @Override
        public int statusCode() {
            return log.responseHeaders().status().code();
        }
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link RequestLog}.
     */
    static String protocol(RequestLog requestLog) {
        return requestLog.scheme().sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    static String serializationFormat(RequestLog requestLog) {
        final SerializationFormat serFmt = requestLog.scheme().serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    private ClientRequestContextAdapter() {}
}
