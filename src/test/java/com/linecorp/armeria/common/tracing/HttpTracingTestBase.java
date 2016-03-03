package com.linecorp.armeria.common.tracing;

import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.http.BraveHttpHeaders;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

public abstract class HttpTracingTestBase {

    public static final SpanId testSpanId = SpanId.create(1, 2, 3L);

    public static HttpHeaders traceHeaders() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(BraveHttpHeaders.Sampled.getName(), "1");
        httpHeader.add(BraveHttpHeaders.TraceId.getName(), "1");
        httpHeader.add(BraveHttpHeaders.SpanId.getName(), "2");
        httpHeader.add(BraveHttpHeaders.ParentSpanId.getName(), "3");
        return httpHeader;
    }

    public static HttpHeaders otherHeaders() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add("X-TEST-HEADER", "xtestheader");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampled() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(BraveHttpHeaders.Sampled.getName(), "0");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampledFalse() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(BraveHttpHeaders.Sampled.getName(), "false");
        return httpHeader;
    }

    public static HttpHeaders traceHeadersNotSampledFalseUpperCase() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(BraveHttpHeaders.Sampled.getName(), "FALSE");
        return httpHeader;
    }

    public static HttpHeaders emptyHttpHeaders() {
        return new DefaultHttpHeaders();
    }

}
