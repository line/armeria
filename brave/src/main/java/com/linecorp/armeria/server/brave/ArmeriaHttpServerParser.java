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

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.brave.SpanTags;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.SpanCustomizer;
import brave.http.HttpRequestParser;
import brave.http.HttpResponse;
import brave.http.HttpResponseParser;
import brave.propagation.TraceContext;

/**
 * Default implementation of {@link HttpRequestParser} and {@link HttpResponseParser} for servers.
 * This parser adds some custom tags and overwrites the name of span if {@link RequestLog#requestContent()}
 * is {@link RpcRequest}.
 * The following tags become available:
 * <ul>
 *   <li>http.url</li>
 *   <li>http.host</li>
 *   <li>http.protocol</li>
 *   <li>http.serfmt</li>
 *   <li>address.remote</li>
 *   <li>address.local</li>
 * </ul>
 */
final class ArmeriaHttpServerParser implements HttpRequestParser, HttpResponseParser {

    private static final ArmeriaHttpServerParser INSTANCE = new ArmeriaHttpServerParser();

    static ArmeriaHttpServerParser get() {
        return INSTANCE;
    }

    private ArmeriaHttpServerParser() {
    }

    @Override
    public void parse(brave.http.HttpRequest request, TraceContext context, SpanCustomizer span) {
        HttpRequestParser.DEFAULT.parse(request, context, span);

        final Object unwrapped = request.unwrap();
        if (!(unwrapped instanceof ServiceRequestContext)) {
            return;
        }

        final ServiceRequestContext ctx = (ServiceRequestContext) unwrapped;
        span.tag(SpanTags.TAG_HTTP_HOST, ctx.request().authority())
            .tag(SpanTags.TAG_HTTP_URL, ctx.request().uri().toString())
            .tag(SpanTags.TAG_HTTP_PROTOCOL, ctx.sessionProtocol().uriText())
            .tag(SpanTags.TAG_ADDRESS_REMOTE, ctx.remoteAddress().toString())
            .tag(SpanTags.TAG_ADDRESS_LOCAL, ctx.localAddress().toString());
    }

    @Override
    public void parse(HttpResponse response, TraceContext context, SpanCustomizer span) {
        HttpResponseParser.DEFAULT.parse(response, context, span);

        final Object res = response.unwrap();
        if (!(res instanceof ServiceRequestContext)) {
            return;
        }

        final ServiceRequestContext ctx = (ServiceRequestContext) res;
        final RequestLog requestLog = ctx.log().ensureComplete();
        @Nullable
        final String serFmt = ServiceRequestContextAdapter.serializationFormat(requestLog);
        if (serFmt != null) {
            span.tag(SpanTags.TAG_HTTP_SERIALIZATION_FORMAT, serFmt);
        }

        span.name(requestLog.name());
    }
}
