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

import brave.http.HttpClientAdapter;
import brave.http.HttpClientHandler;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanTags;
import javax.annotation.Nullable;

final class RequestLogAdapter {
    /**
     * @param log used to parse http properties
     * @param headersBuilder receives headers injected by {@link HttpClientHandler#handleSend(brave.http.HttpClientRequest)}.
     */
    static brave.http.HttpClientRequest asHttpClientRequest(RequestLog log,
        RequestHeadersBuilder headersBuilder) {
        return new HttpClientRequest(log, headersBuilder);
    }

    private static final class HttpClientRequest extends brave.http.HttpClientRequest {
        private final RequestLog log;
        private final RequestHeadersBuilder headersBuilder;

        HttpClientRequest(RequestLog log, RequestHeadersBuilder headersBuilder) {
            this.log = log;
            this.headersBuilder = headersBuilder;
        }

        @Override
        public Object unwrap() {
            return log;
        }

        @Override
        public String method() {
            return log.method().name();
        }

        /**
         * Original implementation is calling {@link HttpClientAdapter#url(Object)} which needs
         * {@link RequestLog#scheme()} is not available at {@link RequestLogAvailability#REQUEST_START}.
         * We need to use {@link RequestLog#path()} directly.
         *
         * @see brave.http.HttpClientRequest#path()
         */
        @Override
        public String path() {
            return log.path();
        }

        @Override
        @Nullable
        public String url() {
            return SpanTags.generateUrl(log);
        }

        @Override
        @Nullable
        public String header(String name) {
            if (!log.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
                return null;
            }
            return log.requestHeaders().get(name);
        }

        @Override
        public void header(String name, String value) {
            headersBuilder.set(name, value);
        }
    }

    static brave.http.HttpClientResponse asHttpClientResponse(RequestLog delegate) {
        return new HttpClientResponse(delegate);
    }

    private static final class HttpClientResponse extends brave.http.HttpClientResponse {
        private final RequestLog delegate;

        HttpClientResponse(RequestLog delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object unwrap() {
            return delegate;
        }

        @Override
        public int statusCode() {
            if (!delegate.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
                return 0;
            }
            return delegate.status().code();
        }
    }

    /**
     * Returns the authority of the {@link Request}.
     */
    @Nullable
    static String authority(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return null;
        }
        return requestLog.authority();
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link Request}.
     */
    @Nullable
    static String protocol(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.SCHEME)) {
            return null;
        }
        return requestLog.scheme().sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    static String serializationFormat(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.SCHEME)) {
            return null;
        }
        final SerializationFormat serFmt = requestLog.scheme().serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    /**
     * Returns the method name if {@link RequestLog#requestContent()} is {@link RpcRequest}.
     */
    @Nullable
    static String rpcMethod(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
            return null;
        }
        final Object requestContent = requestLog.requestContent();
        return requestContent instanceof RpcRequest ? ((RpcRequest) requestContent).method() : null;
    }
}
