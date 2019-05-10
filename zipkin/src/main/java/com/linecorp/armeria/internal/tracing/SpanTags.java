/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.tracing;

import java.net.SocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;

import brave.Span;

/**
 * Adds standard Zipkin tags to a span with the information in a {@link RequestLog}.
 */
public final class SpanTags {

    /** Semi-official annotation for the time the first bytes were sent on the wire. */
    private static final String WIRE_SEND_ANNOTATION = "ws";

    /** Semi-official annotation for the time the first bytes were received on the wire. */
    private static final String WIRE_RECEIVE_ANNOTATION = "wr";

    /**
     * Adds information about the raw HTTP request, RPC request, and endpoint to the span.
     */
    public static void addTags(Span span, RequestLog log) {
        final Scheme scheme = log.scheme();
        final String authority = log.requestHeaders().authority();
        final String path = log.path();

        assert authority != null;
        span.tag("http.host", authority)
            .tag("http.method", log.method().name())
            .tag("http.path", path)
            .tag("http.url", generateUrl(scheme, authority, path, log.query()))
            .tag("http.status_code", log.status().codeAsText())
            .tag("http.protocol", scheme.sessionProtocol().uriText())
            .tag("http.serfmt", scheme.serializationFormat().uriText());

        final Throwable responseCause = log.responseCause();
        if (responseCause != null) {
            span.tag("error", responseCause.toString());
        }

        final SocketAddress raddr = log.context().remoteAddress();
        if (raddr != null) {
            span.tag("address.remote", raddr.toString());
        }

        final SocketAddress laddr = log.context().localAddress();
        if (laddr != null) {
            span.tag("address.local", laddr.toString());
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            span.name(((RpcRequest) requestContent).method());
        }
    }

    private static String generateUrl(Scheme scheme, String authority, String path, @Nullable String query) {
        final SessionProtocol sessionProtocol = scheme.sessionProtocol();
        final StringBuilder uriBuilder = new StringBuilder();
        if (SessionProtocol.httpValues().contains(sessionProtocol)) {
            uriBuilder.append("http://");
        } else if (SessionProtocol.httpsValues().contains(sessionProtocol)) {
            uriBuilder.append("https://");
        } else {
            uriBuilder.append(sessionProtocol.uriText()).append("://");
        }

        uriBuilder.append(authority).append(path);
        if (query != null) {
            uriBuilder.append('?').append(query);
        }

        return uriBuilder.toString();
    }

    public static void logWireSend(Span span, long wireSendTimeNanos, RequestLog requestLog) {
        span.annotate(SpanContextUtil.wallTimeMicros(requestLog, wireSendTimeNanos), WIRE_SEND_ANNOTATION);
    }

    public static void logWireReceive(Span span, long wireSendTimeNanos, RequestLog requestLog) {
        span.annotate(SpanContextUtil.wallTimeMicros(requestLog, wireSendTimeNanos), WIRE_RECEIVE_ANNOTATION);
    }

    private SpanTags() {}
}
