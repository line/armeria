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

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.brave.SpanTags;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Request;
import brave.Response;
import brave.Span;
import brave.SpanCustomizer;
import brave.propagation.TraceContext;

final class ArmeriaServerParser {

    private ArmeriaServerParser() {
    }

    static void parseRequest(Request req, TraceContext context, SpanCustomizer span) {
        final Object unwrapped = req.unwrap();
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

    static void parseResponse(Response res, TraceContext context, SpanCustomizer span) {
        final Object unwrapped = res.unwrap();
        if (!(unwrapped instanceof ServiceRequestContext)) {
            return;
        }
        final ServiceRequestContext ctx = (ServiceRequestContext) unwrapped;
        final RequestLog requestLog = ctx.log().ensureComplete();
        final String serFmt = ServiceRequestContextAdapter.serializationFormat(requestLog);
        if (serFmt != null) {
            span.tag(SpanTags.TAG_HTTP_SERIALIZATION_FORMAT, serFmt);
        }

        final String name = requestLog.name();
        if (name != null) {
            span.name(name);
        }
    }

    static void annotateWireSpan(RequestLog log, Span span) {
        span.start(log.requestStartTimeMicros());
        final Long wireReceiveTimeNanos = log.requestFirstBytesTransferredTimeNanos();
        assert wireReceiveTimeNanos != null;
        SpanTags.logWireReceive(span, wireReceiveTimeNanos, log);

        final Long wireSendTimeNanos = log.responseFirstBytesTransferredTimeNanos();
        if (wireSendTimeNanos != null) {
            SpanTags.logWireSend(span, wireSendTimeNanos, log);
        } else {
            // If the client timed-out the request, we will have never sent any response data at all.
        }
    }
}
