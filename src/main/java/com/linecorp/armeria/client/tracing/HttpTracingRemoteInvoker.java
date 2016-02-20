/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.tracing;

import java.util.Optional;

import javax.annotation.Nullable;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.IdConversion;
import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.http.BraveHttpHeaders;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RemoteInvoker;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * A {@link TracingRemoteInvoker} that uses HTTP headers as a container of trace data.
 */
class HttpTracingRemoteInvoker extends TracingRemoteInvoker {

    HttpTracingRemoteInvoker(RemoteInvoker remoteInvoker, Brave brave) {
        super(remoteInvoker, brave);
    }

    @Override
    protected ClientOptions putTraceData(ClientOptions baseOptions, @Nullable SpanId spanId) {
        final HttpHeaders headers = new DefaultHttpHeaders();

        final Optional<HttpHeaders> baseHttpHeaders = baseOptions.get(ClientOption.HTTP_HEADERS);
        baseHttpHeaders.ifPresent(headers::add);

        if (spanId == null) {
            headers.add(BraveHttpHeaders.Sampled.getName(), "0");
        } else {
            headers.add(BraveHttpHeaders.Sampled.getName(), "1");
            headers.add(BraveHttpHeaders.TraceId.getName(), IdConversion.convertToString(spanId.getTraceId()));
            headers.add(BraveHttpHeaders.SpanId.getName(), IdConversion.convertToString(spanId.getSpanId()));
            if (spanId.getParentSpanId() != null) {
                headers.add(BraveHttpHeaders.ParentSpanId.getName(),
                            IdConversion.convertToString(spanId.getParentSpanId()));
            }
        }

        return ClientOptions.of(baseOptions, ClientOption.HTTP_HEADERS.newValue(headers));
    }

}
