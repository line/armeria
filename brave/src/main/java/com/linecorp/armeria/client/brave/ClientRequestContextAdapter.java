/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.brave;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.brave.SpanContextUtil;

import brave.http.HttpClientAdapter;

/**
 * Wraps a pair of {@link ClientRequestContext} and {@link RequestHeadersBuilder} in an {@link
 * brave.http.HttpClientRequest}.
 */
final class ClientRequestContextAdapter {
    static brave.http.HttpClientRequest asHttpClientRequest(ClientRequestContext ctx,
        RequestHeadersBuilder headersBuilder) {
        return new HttpClientRequest(ctx, headersBuilder);
    }

    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpClientRequest extends brave.http.HttpClientRequest {
        private final ClientRequestContext ctx;
        private final RequestHeadersBuilder headersBuilder;

        HttpClientRequest(ClientRequestContext ctx, RequestHeadersBuilder headersBuilder) {
            this.ctx = ctx;
            this.headersBuilder = headersBuilder;
        }

        @Override
        public ClientRequestContext unwrap() {
            return ctx;
        }

        @Override
        public String method() {
            return ctx.method().name();
        }

        /**
         * Original implementation is calling {@link HttpClientAdapter#url(Object)} which needs {@link
         * RequestLog#scheme()}, but because {@link RequestLog#scheme()} is not always available, we need to
         * use {@link RequestContext#path()} directly.
         *
         * @see brave.http.HttpClientRequest#path()
         */
        @Override
        public String path() {
            return ctx.path();
        }

        @Override
        @Nullable
        public String url() {
            final HttpRequest req = ctx.request();
            return req != null ? req.uri().toString() : null;
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

        @Override
        public long startTimestamp() {
            final RequestLogAccess logAccess = ctx.log();
            if (logAccess.isAvailable(RequestLogProperty.REQUEST_START_TIME)) {
                return logAccess.partial().requestStartTimeMicros();
            } else {
                return 0;
            }
        }
    }

    static brave.http.HttpClientResponse asHttpClientResponse(RequestLog log) {
        return new HttpClientResponse(log);
    }

    /**
     * Note that this class is used only after {@link RequestLog} is complete.
     */
    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpClientResponse extends brave.http.HttpClientResponse {
        private final RequestLog log;

        HttpClientResponse(RequestLog log) {
            assert log.isComplete() : log;
            this.log = log;
        }

        @Override
        public ClientRequestContext unwrap() {
            return (ClientRequestContext) log.context();
        }

        @Override
        public int statusCode() {
            return log.responseHeaders().status().code();
        }

        @Override
        public long finishTimestamp() {
            return SpanContextUtil.wallTimeMicros(log, log.responseEndTimeNanos());
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
