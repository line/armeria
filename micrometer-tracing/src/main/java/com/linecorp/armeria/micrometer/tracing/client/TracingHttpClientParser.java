/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.micrometer.tracing.client;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.net.SocketAddress;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.micrometer.tracing.internal.SpanTags;

import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.http.HttpRequest;
import io.micrometer.tracing.http.HttpRequestParser;
import io.micrometer.tracing.http.HttpResponse;
import io.micrometer.tracing.http.HttpResponseParser;

public final class TracingHttpClientParser implements HttpRequestParser, HttpResponseParser {

    private static final TracingHttpClientParser INSTANCE = new TracingHttpClientParser();

    public static TracingHttpClientParser of() {
        return INSTANCE;
    }

    private TracingHttpClientParser() {}

    @Override
    public void parse(HttpRequest request, TraceContext context, SpanCustomizer span) {

        final Object unwrapped = request.unwrap();
        if (!(unwrapped instanceof ClientRequestContext)) {
            return;
        }

        final ClientRequestContext ctx = (ClientRequestContext) unwrapped;
        final com.linecorp.armeria.common.HttpRequest httpReq = ctx.request();
        if (httpReq == null) {
            // Should never reach here because BraveClient is an HTTP-level decorator.
            return;
        }

        span.tag(SpanTags.TAG_HTTP_HOST, firstNonNull(ctx.authority(), "UNKNOWN"))
            .tag(SpanTags.TAG_HTTP_URL, ctx.uri().toString());
    }

    @Override
    public void parse(HttpResponse response, TraceContext context, SpanCustomizer span) {
        final Object res = response.unwrap();
        if (!(res instanceof ClientRequestContext)) {
            return;
        }

        final ClientRequestContext ctx = (ClientRequestContext) res;
        final RequestLog log = ctx.log().ensureComplete();
        span.tag(SpanTags.TAG_HTTP_PROTOCOL, ClientRequestContextAdapter.protocol(log));

        final String serFmt = ClientRequestContextAdapter.serializationFormat(log);
        if (serFmt != null) {
            span.tag(SpanTags.TAG_HTTP_SERIALIZATION_FORMAT, serFmt);
        }

        final SocketAddress raddr = ctx.remoteAddress();
        if (raddr != null) {
            span.tag(SpanTags.TAG_ADDRESS_REMOTE, raddr.toString());
        }

        final SocketAddress laddr = ctx.localAddress();
        if (laddr != null) {
            span.tag(SpanTags.TAG_ADDRESS_LOCAL, laddr.toString());
        }

        final String name = log.name();
        if (name != null) {
            span.name(name);
        }
    }
}
