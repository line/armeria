package com.linecorp.armeria.common.tracing;

import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.PARENT_SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SAMPLED;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.TRACE_ID;

import com.github.kristofa.brave.SpanId;

import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaders;

import io.netty.util.AsciiString;

public abstract class HttpTracingTestBase {

    public static final SpanId testSpanId = SpanId.builder().traceId(1).spanId(2).parentId(3L).build();

    public static HttpHeaders traceHeaders() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(SAMPLED, "1");
        httpHeader.add(TRACE_ID, "1");
        httpHeader.add(SPAN_ID, "2");
        httpHeader.add(PARENT_SPAN_ID, "3");
        return httpHeader;
    }

    public static HttpHeaders otherHeaders() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(AsciiString.of("x-test-header"), "test-value");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampled() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(SAMPLED, "0");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampledFalse() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(SAMPLED, "false");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampledFalseUpperCase() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(SAMPLED, "FALSE");
        return httpHeader;
    }

    public static HttpHeaders emptyHttpHeaders() {
        return new DefaultHttpHeaders();
    }
}
