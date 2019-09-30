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

package com.linecorp.armeria.server.brave;

import java.util.List;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanContextUtil;
import com.linecorp.armeria.internal.brave.SpanTags;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
import brave.http.HttpServerAdapter;
import brave.http.HttpServerHandler;

/**
 * Wraps {@link ServiceRequestContext} in an {@link brave.http.HttpServerRequest}, for use in
 * {@link HttpServerHandler}.
 */
final class ServiceRequestContextAdapter {
    static brave.http.HttpServerRequest asHttpServerRequest(ServiceRequestContext ctx) {
        return new HttpServerRequest(ctx);
    }

    private static final class HttpServerRequest extends brave.http.HttpServerRequest {
        private final ServiceRequestContext ctx;

        HttpServerRequest(ServiceRequestContext ctx) {
            this.ctx = ctx;
        }

        /**
         * This sets the client IP:port to the {@link RequestContext#remoteAddress()}
         * if the {@linkplain HttpServerAdapter#parseClientIpAndPort default parsing} fails.
         */
        @Override
        public boolean parseClientIpAndPort(Span span) {
            return SpanTags.updateRemoteEndpoint(span, ctx);
        }

        @Override
        public ServiceRequestContext unwrap() {
            return ctx;
        }

        @Override
        public String method() {
            return ctx.method().name();
        }

        /**
         * Original implementation is calling {@link HttpServerAdapter#url(Object)} which needs {@link
         * RequestLog#scheme()} is not available at {@link RequestLogAvailability#REQUEST_START}. We
         * need to use {@link RequestLog#path()} directly.
         *
         * @see brave.http.HttpServerRequest#path()
         */
        @Override
        public String path() {
            return ctx.path();
        }

        @Override
        @Nullable
        public String url() {
            return SpanTags.generateUrl(ctx.log());
        }

        @Override
        @Nullable
        public String header(String name) {
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
                return null;
            }
            return ctx.log().requestHeaders().get(name);
        }

        @Override
        public long startTimestamp() {
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_START)) {
                return 0L;
            }
            return ctx.log().requestStartTimeMicros();
        }
    }

    static brave.http.HttpServerResponse asHttpServerResponse(ServiceRequestContext ctx) {
        return new HttpServerResponse(ctx);
    }

    private static final class HttpServerResponse extends brave.http.HttpServerResponse {
        private final ServiceRequestContext ctx;

        HttpServerResponse(ServiceRequestContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public ServiceRequestContext unwrap() {
            return ctx;
        }

        @Override
        public int statusCode() {
            if (!ctx.log().isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
                return 0;
            }
            return ctx.log().status().code();
        }

        @Override
        public String method() {
            return ctx.method().name();
        }

        @Override
        @Nullable
        public String route() {
            final Route route = ctx.route();
            final List<String> paths = route.paths();
            switch (route.pathType()) {
                case EXACT:
                case PREFIX:
                case PARAMETERIZED:
                    return paths.get(1);
                case REGEX:
                    return paths.get(paths.size() - 1);
                case REGEX_WITH_PREFIX:
                    return paths.get(1) + paths.get(0);
            }
            return null;
        }

        @Override
        public long finishTimestamp() {
            if (!ctx.log().isAvailable(RequestLogAvailability.RESPONSE_END)) {
                return 0L;
            }
            return SpanContextUtil.wallTimeMicros(ctx.log(), ctx.log().responseEndTimeNanos());
        }
    }

    /**
     * Returns the authority of the {@link RequestLog}.
     */
    @Nullable
    static String authority(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return null;
        }
        return requestLog.authority();
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link RequestLog}.
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

    private ServiceRequestContextAdapter() {
    }
}
