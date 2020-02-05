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

package com.linecorp.armeria.internal.common.brave;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.linecorp.armeria.common.RequestContext;
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

    public static final String TAG_HTTP_HOST = "http.host";
    public static final String TAG_HTTP_URL = "http.url";
    public static final String TAG_HTTP_PROTOCOL = "http.protocol";
    public static final String TAG_HTTP_SERIALIZATION_FORMAT = "http.serfmt";
    public static final String TAG_ADDRESS_REMOTE = "address.remote";
    public static final String TAG_ADDRESS_LOCAL = "address.local";

    public static void logWireSend(Span span, long wireSendTimeNanos, RequestLog requestLog) {
        span.annotate(SpanContextUtil.wallTimeMicros(requestLog, wireSendTimeNanos),
                      WIRE_SEND_ANNOTATION);
    }

    public static void logWireReceive(Span span, long wireReceiveTimeNanos, RequestLog requestLog) {
        span.annotate(SpanContextUtil.wallTimeMicros(requestLog, wireReceiveTimeNanos),
                      WIRE_RECEIVE_ANNOTATION);
    }

    public static boolean updateRemoteEndpoint(Span span, RequestContext ctx) {
        final SocketAddress remoteAddress = ctx.remoteAddress();
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
