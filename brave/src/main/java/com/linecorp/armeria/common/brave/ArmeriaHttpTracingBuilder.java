/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.common.brave;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.brave.ArmeriaHttpClientParser;
import com.linecorp.armeria.server.brave.ArmeriaHttpServerParser;

import brave.Tracing;
import brave.http.HttpClientParser;
import brave.http.HttpSampler;
import brave.http.HttpServerParser;
import brave.http.HttpTracing;

/**
 * A helper class for creating a new {@link HttpTracing} instance.
 * For example,
 *
 * <pre>{@code
 * HttpTracing httpTracing = new ArmeriaHttpTracingBuilder(tracing)
 *     .build();
 * }</pre>
 *
 */
public class ArmeriaHttpTracingBuilder {
    private final HttpTracing.Builder httpTracingBuilder;
    @Nullable
    private HttpClientParser clientParser;
    @Nullable
    private HttpServerParser serverParser;

    /**
     * Creates a {@link ArmeriaHttpTracingBuilder} with the specified {@link Tracing}.
     */
    public ArmeriaHttpTracingBuilder(Tracing tracing) {
        requireNonNull(tracing, "tracing");
        httpTracingBuilder = HttpTracing.newBuilder(tracing);
    }

    /**
     * Overrides the tagging policy for http client spans.
     * @see HttpTracing.Builder#clientParser(HttpClientParser)
     */
    public ArmeriaHttpTracingBuilder clientParser(HttpClientParser clientParser) {
        clientParser = requireNonNull(clientParser, "clientParser");
        this.clientParser = clientParser;
        return this;
    }

    /**
     * Overrides the tagging policy for http server spans.
     * @see HttpTracing.Builder#serverParser(HttpServerParser)
     */
    public ArmeriaHttpTracingBuilder serverParser(HttpServerParser serverParser) {
        serverParser = requireNonNull(serverParser, "serverParser");
        this.serverParser = serverParser;
        return this;
    }

    /**
     * Returns an overriding sampling decision for a new trace. Defaults to ignore the request and use
     * the {@link HttpSampler#TRACE_ID trace ID instead}.
     *
     * <p>This decision happens when a trace was not yet started in process. For example, you may be
     * making an http request as a part of booting your application. You may want to opt-out of
     * tracing client requests that did not originate from a server request.
     * @see HttpTracing#clientSampler()
     */
    public ArmeriaHttpTracingBuilder clientSampler(HttpSampler clientSampler) {
        httpTracingBuilder.clientSampler(clientSampler);
        return this;
    }

    /**
     * Returns an overriding sampling decision for a new trace. Defaults to ignore the request and use
     * the {@link HttpSampler#TRACE_ID trace ID instead}.
     *
     * <p>This decision happens when trace IDs were not in headers, or a sampling decision has not
     * yet been made. For example, if a trace is already in progress, this function is not called. You
     * can implement this to skip paths that you never want to trace.
     * @see HttpTracing#serverSampler()
     */
    public ArmeriaHttpTracingBuilder serverSampler(HttpSampler serverSampler) {
        httpTracingBuilder.serverSampler(serverSampler);
        return this;
    }

    /**
     * Returns a newly-created {@link HttpTracing} based on the properties of this builder.
     */
    public HttpTracing build() {
        if (clientParser == null) {
            httpTracingBuilder.clientParser(new ArmeriaHttpClientParser());
        }
        if (serverParser == null) {
            httpTracingBuilder.serverParser(new ArmeriaHttpServerParser());
        }
        return httpTracingBuilder.build();
    }
}
