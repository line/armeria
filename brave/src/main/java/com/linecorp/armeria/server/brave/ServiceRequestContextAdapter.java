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
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.brave.SpanContextUtil;
import com.linecorp.armeria.internal.common.brave.SpanTags;
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

    @SuppressWarnings("ClassNameSameAsAncestorName")
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
         * RequestLog#scheme()}, but because {@link RequestLog#scheme()} is not always available, we need to
         * use {@link RequestContext#path()} directly.
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
            return ctx.request().uri().toString();
        }

        @Override
        @Nullable
        public String header(String name) {
            return ctx.request().headers().get(name);
        }

        @Override
        public long startTimestamp() {
            return ctx.log().ensureAvailable(RequestLogProperty.REQUEST_START_TIME).requestStartTimeMicros();
        }
    }

    static brave.http.HttpServerResponse asHttpServerResponse(RequestLog log) {
        return new HttpServerResponse(log);
    }

    /**
     * Note that this class is used only after {@link RequestLog} is complete.
     */
    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpServerResponse extends brave.http.HttpServerResponse {
        private final RequestLog log;

        HttpServerResponse(RequestLog log) {
            assert log.isComplete() : log;
            this.log = log;
        }

        @Override
        public ServiceRequestContext unwrap() {
            return (ServiceRequestContext) log.context();
        }

        @Override
        public int statusCode() {
            return log.responseHeaders().status().code();
        }

        @Override
        public String method() {
            return log.requestHeaders().method().name();
        }

        @Override
        @Nullable
        public String route() {
            final Route route = ((ServiceRequestContext) log.context()).route();
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
            return SpanContextUtil.wallTimeMicros(log, log.responseEndTimeNanos());
        }
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    static String serializationFormat(RequestLog requestLog) {
        final SerializationFormat serFmt = requestLog.scheme().serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    private ServiceRequestContextAdapter() {}
}
