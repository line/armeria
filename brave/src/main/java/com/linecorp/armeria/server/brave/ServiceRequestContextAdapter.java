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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.brave.SpanContextUtil;
import com.linecorp.armeria.internal.common.brave.SpanTags;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
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
         * if the {@linkplain #parseClientIpFromXForwardedFor default parsing} fails.
         */
        @Override
        public boolean parseClientIpAndPort(Span span) {
            return parseClientIpFromXForwardedFor(span) || SpanTags.updateRemoteEndpoint(span, ctx);
        }

        @Override
        public ServiceRequestContext unwrap() {
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
        public String route() {
            return ctx.config().route().patternString();
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

    static brave.http.HttpServerResponse asHttpServerResponse(
            RequestLog log, brave.http.HttpServerRequest request) {
        return new HttpServerResponse(log, request);
    }

    /**
     * Note that this class is used only after {@link RequestLog} is complete.
     */
    @SuppressWarnings("ClassNameSameAsAncestorName")
    private static final class HttpServerResponse extends brave.http.HttpServerResponse {
        private final RequestLog log;
        private final brave.http.HttpServerRequest request;

        HttpServerResponse(RequestLog log, brave.http.HttpServerRequest request) {
            assert log.isComplete() : log;
            this.log = log;
            this.request = request;
        }

        @Override
        public ServiceRequestContext unwrap() {
            return (ServiceRequestContext) log.context();
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

        @Override
        public brave.http.HttpServerRequest request() {
            return request;
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
