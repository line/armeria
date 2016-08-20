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

package com.linecorp.armeria.internal.tracing;

import java.util.Locale;

import com.github.kristofa.brave.http.BraveHttpHeaders;

import io.netty.util.AsciiString;

/**
 * Provides HTTP/2 header names for Zipkin call tracing.
 */
public final class BraveHttpHeaderNames {

    /**
     * {@code "x-b3-sampled"}
     *
     * @see BraveHttpHeaders#Sampled
     */
    public static final AsciiString SAMPLED =
            AsciiString.of(BraveHttpHeaders.Sampled.getName().toLowerCase(Locale.ENGLISH));

    /**
     * {@code "x-b3-traceid"}
     *
     * @see BraveHttpHeaders#TraceId
     */
    public static final AsciiString TRACE_ID =
            AsciiString.of(BraveHttpHeaders.TraceId.getName().toLowerCase(Locale.ENGLISH));

    /**
     * {@code "x-b3-spanid"}
     *
     * @see BraveHttpHeaders#SpanId
     */
    public static final AsciiString SPAN_ID =
            AsciiString.of(BraveHttpHeaders.SpanId.getName().toLowerCase(Locale.ENGLISH));

    /**
     * {@code "x-b3-parentspanid"}
     *
     * @see BraveHttpHeaders#ParentSpanId
     */
    public static final AsciiString PARENT_SPAN_ID =
            AsciiString.of(BraveHttpHeaders.ParentSpanId.getName().toLowerCase(Locale.ENGLISH));

    private BraveHttpHeaderNames() {}
}
