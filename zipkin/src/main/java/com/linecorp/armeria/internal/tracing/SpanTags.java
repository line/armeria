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

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;

import brave.Span;
import zipkin.Constants;
import zipkin.TraceKeys;

/**
 * Adds standard Zipkin tags to a span with the information in a {@link RequestLog}.
 */
public final class SpanTags {

    /**
     * Adds information about the raw HTTP request, RPC request, and endpoint to the span.
     */
    public static void addTags(Span span, RequestLog log) {
        if (log.host() != null) {
            span.tag(TraceKeys.HTTP_HOST, log.host());
        }
        final StringBuilder uriBuilder = new StringBuilder()
                .append(log.scheme().uriText())
                .append("://")
                .append(log.host())
                .append(log.path());
        if (log.query() != null) {
            uriBuilder.append('?').append(log.query());
        }
        span.tag(TraceKeys.HTTP_METHOD, log.method().name())
            .tag(TraceKeys.HTTP_PATH, log.path())
            .tag(TraceKeys.HTTP_URL, uriBuilder.toString())
            .tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(log.statusCode()));
        if (log.responseCause() != null) {
            span.tag(Constants.ERROR, log.responseCause().toString());
        }

        if (log.context().remoteAddress() != null) {
            span.tag("address.remote", log.context().remoteAddress().toString());
        }
        if (log.context().localAddress() != null) {
            span.tag("address.local", log.context().localAddress().toString());
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            span.name(((RpcRequest) requestContent).method());
        }
    }

    private SpanTags() {}
}
