/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.handler.SpanHandler.Cause;
import brave.propagation.TraceContext;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.brave.ZipkinSpanHandler;

/**
 * This tests that zipkin-reporter-brave types work without adding any explicit
 * dependencies.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class CompilationTest {

    TraceContext context = TraceContext.newBuilder().traceId(1).spanId(1).build();
    MutableSpan span = new MutableSpan();
    @Mock
    BytesMessageSender sender;

    @Test
    void zipkinSpanHandler() {
        final SpanHandler handler = ZipkinSpanHandler.create(Reporter.NOOP);
        assertThat(handler.begin(context, span, null)).isTrue();
        assertThat(handler.end(context, span, Cause.FINISHED)).isTrue();
    }

    @Test
    void asyncZipkinSpanHandler() {
        when(sender.encoding()).thenReturn(Encoding.JSON);

        try (AsyncZipkinSpanHandler handler = AsyncZipkinSpanHandler.newBuilder(sender).build()) {
            assertThat(handler.begin(context, span, null)).isTrue();
            assertThat(handler.end(context, span, Cause.FINISHED)).isTrue();
        }
    }
}
