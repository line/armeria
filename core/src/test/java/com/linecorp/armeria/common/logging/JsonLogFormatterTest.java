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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
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
        System.err.println(requestLog);
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
}
