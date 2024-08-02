/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

class JsonLogFormatterTest {

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void formatRequest() {
        final LogFormatter logFormatter = LogFormatter.ofJson();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog)
                .matches("^\\{\"type\":\"request\",\"startTime\":\".+\",\"length\":\".+\"," +
                         "\"duration\":\".+\",\"scheme\":\".+\",\"name\":\".+\",\"headers\":\\{\".+\"}}$");
    }

    @Test
    void formatResponse() {
        final LogFormatter logFormatter = LogFormatter.ofJson();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endResponse();
        final String responseLog = logFormatter.formatResponse(log);
        assertThat(responseLog)
                .matches("^\\{\"type\":\"response\",\"startTime\":\".+\",\"length\":\".+\"," +
                         "\"duration\":\".+\",\"totalDuration\":\".+\",\"headers\":\\{\".+\"}}$");
    }

    @Test
    void derivedLog() {
        final LogFormatter logFormatter = LogFormatter.ofJson();
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/format");
        final ClientRequestContext ctx = ClientRequestContext.of(request);
        final ClientRequestContext derivedCtx =
                ctx.newDerivedContext(RequestId.of(1), request, null, Endpoint.of("127.0.0.1"));
        final DefaultRequestLog log = (DefaultRequestLog) derivedCtx.log();
        ctx.logBuilder().addChild(log);
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog)
                .matches("^\\{\"type\":\"request\",\"startTime\":\".+\",\"length\":\".+\"," +
                         "\"duration\":\".+\",\"scheme\":\".+\",\"name\":\".+\",\"headers\":\\{\".+\"}" +
                         ",\"currentAttempt\":1}$");
    }

    @Test
    void maskSensitiveHeadersByDefault() {
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .responseHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson().build())
                                                      .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/hello"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK, "Set-Cookie", "Armeria=awesome"));
        log.endResponse();

        final String responseLog = logFormatter.formatResponse(log);
        final Matcher matcher1 = Pattern.compile("\"set-cookie\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo("****");
    }

    @Test
    void defaultSensitiveHeadersShouldBeOverridable() {
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .responseHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson()
                                                                              .sensitiveHeaders("Cache-Control")
                                                                              .build())
                                                      .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/hello"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK, "Set-Cookie", "armeria=fun",
                                               "Cache-Control", "no-cache"));
        log.endResponse();

        final String responseLog = logFormatter.formatResponse(log);
        final Matcher matcher1 = Pattern.compile("\"set-cookie\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo("armeria=fun");

        final Matcher matcher2 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher2.find()).isTrue();
        assertThat(matcher2.group(1)).isEqualTo("****");
    }

    @Test
    void maskRequestHeaders() {
        final HeaderMaskingFunction maskingFunction = (name, value) -> "****armeria****";
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .requestHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson()
                                                                              .sensitiveHeaders("accept")
                                                                              .maskingFunction(maskingFunction)
                                                                              .build())
                                                      .build();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/hello",
                                                                 "Accept", "text/html",
                                                                 "Cache-Control", "no-cache"));

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);

        final Matcher matcher1 = Pattern.compile("\"accept\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo(maskingFunction.mask(HttpHeaderNames.ACCEPT, "text/html"));

        final Matcher matcher2 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher2.find()).isTrue();
        assertThat(matcher2.group(1)).isEqualTo("no-cache");
    }

    @Test
    void maskResponseHeaders() {
        final HeaderMaskingFunction maskingFunction = (name, value) -> "****armeria****";
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .responseHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson()
                                                                              .sensitiveHeaders("content-type")
                                                                              .maskingFunction(maskingFunction)
                                                                              .build())
                                                      .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/hello"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                               "Content-Type", "text/html",
                                               "Cache-Control", "no-cache"));
        log.endResponse();
        final String responseLog = logFormatter.formatResponse(log);
        final Matcher matcher1 = Pattern.compile("\"content-type\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo(
                maskingFunction.mask(HttpHeaderNames.CONTENT_TYPE, "text/html"));

        final Matcher matcher2 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher2.find()).isTrue();
        assertThat(matcher2.group(1)).isEqualTo("no-cache");
    }

    @Test
    void maskRequestHeadersWithDuplicateHeaderName() {
        final HeaderMaskingFunction maskingFunction = (name, value) -> "****armeria****";
        final HeadersSanitizer<JsonNode> headersSanitizer =
                HeadersSanitizer.builderForJson()
                                .sensitiveHeaders("accept-encoding")
                                .sensitiveHeaders("content-type")
                                .maskingFunction(maskingFunction)
                                .build();
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .requestHeadersSanitizer(headersSanitizer)
                                                      .build();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/hello",
                                                                 "Accept-Encoding", "gzip",
                                                                 "Accept-Encoding", "deflate"));

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);

        final Matcher matcher1 = Pattern.compile("\"accept-encoding\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo(
                "[" + maskingFunction.mask(HttpHeaderNames.ACCEPT_ENCODING, "gzip") + ", " +
                maskingFunction.mask(HttpHeaderNames.ACCEPT_ENCODING, "deflate") + "]");
    }

    @Test
    void removeSensitiveHeaders() {
        final LogFormatter logFormatter =
                LogFormatter.builderForJson()
                            .responseHeadersSanitizer(
                                    HeadersSanitizer.builderForJson()
                                                    .sensitiveHeaders("set-cookie", "multiple-header")
                                                    .maskingFunction((name, value) -> null)
                                                    .build())
                            .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/hello"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.SET_COOKIE, "armeria=fun",
                                               "multiple-header", "armeria1", "multiple-header", "armeria2",
                                               HttpHeaderNames.CACHE_CONTROL, "no-cache"));
        log.endResponse();

        final String responseLog = logFormatter.formatResponse(log);
        final Matcher matcher1 = Pattern.compile("\"set-cookie\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher1.find()).isFalse();
        final Matcher matcher2 = Pattern.compile("\"multiple-header\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher2.find()).isFalse();
        final Matcher matcher3 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher3.find()).isTrue();
        assertThat(matcher3.group(1)).isEqualTo("no-cache");
    }

    static Stream<Arguments> connectionTimingsAreLoggedIfExistParams() {
        return Stream.of(
                Arguments.of(ClientConnectionTimings.builder()
                                                    .build()),
                Arguments.of(ClientConnectionTimings.builder()
                                                    .dnsResolutionEnd()
                                                    .build()),
                Arguments.of(ClientConnectionTimings.builder()
                                                    .tlsHandshakeStart()
                                                    .tlsHandshakeEnd()
                                                    .build()),
                Arguments.of(ClientConnectionTimings.builder()
                                                    .dnsResolutionEnd()
                                                    .pendingAcquisitionStart()
                                                    .pendingAcquisitionEnd()
                                                    .socketConnectStart()
                                                    .socketConnectEnd()
                                                    .tlsHandshakeStart()
                                                    .tlsHandshakeEnd()
                                                    .build())
        );
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("connectionTimingsAreLoggedIfExistParams")
    void connectionTimingsAreLoggedIfExist(@Nullable ClientConnectionTimings timings) {
        final LogFormatter logFormatter = JsonLogFormatter.DEFAULT_INSTANCE;
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"));
        final ClientRequestContextBuilder builder = ClientRequestContext.builder(req);
        if (timings != null) {
            builder.connectionTimings(timings);
        }
        final ClientRequestContext ctx = builder.build();
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest();
        final String formatted = logFormatter.formatRequest(logBuilder.partial());
        if (timings == null) {
            assertThatJson(formatted).node("connection").isAbsent();
            return;
        }

        assertThatJson(formatted)
                .node("connection.total.durationNanos")
                .isEqualTo(timings.connectionAcquisitionDurationNanos());

        if (timings.dnsResolutionDurationNanos() >= 0) {
            assertThatJson(formatted)
                    .node("connection.dns.durationNanos")
                    .isEqualTo(timings.dnsResolutionDurationNanos());
        } else {
            assertThatJson(formatted).node("connection.dns.durationNanos").isAbsent();
        }

        if (timings.pendingAcquisitionDurationNanos() >= 0) {
            assertThatJson(formatted)
                    .node("connection.pending.durationNanos")
                    .isEqualTo(timings.pendingAcquisitionDurationNanos());
        } else {
            assertThatJson(formatted).node("connection.pending.durationNanos").isAbsent();
        }

        if (timings.socketConnectDurationNanos() >= 0) {
            assertThatJson(formatted)
                    .node("connection.socket.durationNanos")
                    .isEqualTo(timings.socketConnectDurationNanos());
        } else {
            assertThatJson(formatted).node("connection.socket.durationNanos").isAbsent();
        }

        if (timings.tlsHandshakeDurationNanos() >= 0) {
            assertThatJson(formatted)
                    .node("connection.tls.durationNanos")
                    .isEqualTo(timings.tlsHandshakeDurationNanos());
        } else {
            assertThatJson(formatted).node("connection.tls.durationNanos").isAbsent();
        }
    }
}
