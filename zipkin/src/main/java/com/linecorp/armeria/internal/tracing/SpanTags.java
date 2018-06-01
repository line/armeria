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

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;

import brave.Span;

/**
 * Adds standard Zipkin tags to a span with the information in a {@link RequestLog}.
 */
public final class SpanTags {

    /**
     * Adds information about the raw HTTP request, RPC request, and endpoint to the span.
     */
    public static void addTags(Span span, RequestLog log) {
        final String host = log.requestHeaders().authority();
        assert host != null;
        span.tag("http.host", host);
        final StringBuilder uriBuilder = new StringBuilder()
                .append(log.scheme().uriText())
                .append("://")
                .append(host)
                .append(log.path());
        if (log.query() != null) {
            uriBuilder.append('?').append(log.query());
        }
        span.tag("http.method", log.method().name())
            .tag("http.path", log.path())
            .tag("http.url", uriBuilder.toString())
            .tag("http.status_code", log.status().codeAsText());
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

    private SpanTags() {}
}
