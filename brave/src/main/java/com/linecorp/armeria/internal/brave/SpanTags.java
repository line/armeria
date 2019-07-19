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

package com.linecorp.armeria.internal.brave;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;

import brave.Span;

/**
 * Adds standard Zipkin tags to a span with the information in a {@link RequestLog}.
 */
public final class SpanTags {

    /** Semi-official annotation for the time the first bytes were sent on the wire. */
    private static final String WIRE_SEND_ANNOTATION = "ws";

    /** Semi-official annotation for the time the first bytes were received on the wire. */
    private static final String WIRE_RECEIVE_ANNOTATION = "wr";

    public static final String TAG_HTTP_HOST = "http.host";
    public static final String TAG_HTTP_METHOD = "http.method";
    public static final String TAG_HTTP_PATH = "http.path";
    public static final String TAG_HTTP_URL = "http.url";
    public static final String TAG_HTTP_STATUS_CODE = "http.status_code";
    public static final String TAG_HTTP_PROTOCOL = "http.protocol";
    public static final String TAG_HTTP_SERIALIZATION_FORMAT = "http.serfmt";
    public static final String TAG_ERROR = "error";
    public static final String TAG_ADDRESS_REMOTE = "address.remote";
    public static final String TAG_ADDRESS_LOCAL = "address.local";

    /**
     * Adds information about the raw HTTP request, RPC request, and endpoint to the span.
     */
    public static void addTags(Span span, RequestLog log) {
        final Scheme scheme = log.scheme();
        final String authority = log.requestHeaders().authority();
        final String path = log.path();

        assert authority != null;
        span.tag(TAG_HTTP_HOST, authority)
            .tag(TAG_HTTP_METHOD, log.method().name())
            .tag(TAG_HTTP_PATH, path)
            .tag(TAG_HTTP_URL, generateUrl(log))
            .tag(TAG_HTTP_STATUS_CODE, log.status().codeAsText())
            .tag(TAG_HTTP_PROTOCOL, scheme.sessionProtocol().uriText());

        final SerializationFormat serFmt = scheme.serializationFormat();
        if (serFmt != SerializationFormat.NONE) {
            span.tag(TAG_HTTP_SERIALIZATION_FORMAT, serFmt.uriText());
        }

        final Throwable responseCause = log.responseCause();
        if (responseCause != null) {
            span.tag(TAG_ERROR, responseCause.toString());
        }

        final SocketAddress raddr = log.context().remoteAddress();
        if (raddr != null) {
            span.tag(TAG_ADDRESS_REMOTE, raddr.toString());
        }

        final SocketAddress laddr = log.context().localAddress();
        if (laddr != null) {
            span.tag(TAG_ADDRESS_LOCAL, laddr.toString());
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            span.name(((RpcRequest) requestContent).method());
        }
    }

    /**
     * Url needs {@link RequestLogAvailability#SCHEME} and {@link RequestLogAvailability#REQUEST_HEADERS}.
     * Return null if this property is not available yet.
     */
    @Nullable
    public static String generateUrl(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.SCHEME) ||
            !requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return null;
        }
        final Scheme scheme = requestLog.scheme();
        final String authority = requestLog.authority();
        final String path = requestLog.path();
        final String query = requestLog.query();
        final SessionProtocol sessionProtocol = scheme.sessionProtocol();
        final String uriScheme;
        if (SessionProtocol.httpValues().contains(sessionProtocol)) {
            uriScheme = "http://";
        } else if (SessionProtocol.httpsValues().contains(sessionProtocol)) {
            uriScheme = "https://";
        } else {
            uriScheme = sessionProtocol.uriText() + "://";
        }

        final StringBuilder uriBuilder = new StringBuilder(
                uriScheme.length() + authority.length() + path.length() +
                (query != null ? query.length() + 1 : 0));

        uriBuilder.append(uriScheme)
                  .append(authority)
                  .append(path);

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

    public static boolean updateRemoteEndpoint(Span span, RequestLog log) {
        final SocketAddress remoteAddress = log.context().remoteAddress();
        final InetAddress address;
        final int port;
        if (remoteAddress instanceof InetSocketAddress) {
            final InetSocketAddress socketAddress = (InetSocketAddress) remoteAddress;
            address = socketAddress.getAddress();
            port = socketAddress.getPort();
        } else {
            address = null;
            port = 0;
        }
        if (address != null) {
            return span.remoteIpAndPort(address.getHostAddress(), port);
        }
        return false;
    }

    private SpanTags() {}
}
