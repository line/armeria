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

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HeadersSanitizer;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

class JsonLogFormatterTest {

    private final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();

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
    void maskRequestHeaders() {
        final Function<String, String> maskingFunction = (header) -> "****armeria****";
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .requestHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson()
                                                                              .maskHeaders("cookie",
                                                                                           "authorization")
                                                                              .mask(maskingFunction)
                                                                              .build())
                                                      .build();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/hello",
                                                                 "Cookie", "Armeria=awesome",
                                                                 "Authorization", "Basic XXX==",
                                                                 "Cache-Control", "no-cache"));

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);

        final Matcher matcher1 = Pattern.compile("\"cookie\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo(maskingFunction.apply("Armeria=awesome"));

        final Matcher matcher2 = Pattern.compile("\"authorization\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher2.find()).isTrue();
        assertThat(matcher2.group(1)).isEqualTo(maskingFunction.apply("Basic XXX=="));

        final Matcher matcher3 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(requestLog);
        assertThat(matcher3.find()).isTrue();
        assertThat(matcher3.group(1)).isEqualTo("no-cache");
    }

    @Test
    void maskResponseHeaders() {
        final Function<String, String> maskingFunction = (header) -> "****armeria****";
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                                                      .responseHeadersSanitizer(
                                                              HeadersSanitizer.builderForJson()
                                                                              .maskHeaders("content-type",
                                                                                           "set-cookie")
                                                                              .mask(maskingFunction)
                                                                              .build())
                                                      .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/hello"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                               "Content-Type", "text/html",
                                               "Set-Cookie", "Armeria=awesome",
                                               "Cache-Control", "no-cache"));
        log.endResponse();
        final String responseLog = logFormatter.formatResponse(log);
        final Matcher matcher1 = Pattern.compile("\"content-type\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher1.find()).isTrue();
        assertThat(matcher1.group(1)).isEqualTo(maskingFunction.apply("text/html"));

        final Matcher matcher2 = Pattern.compile("\"set-cookie\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher2.find()).isTrue();
        assertThat(matcher2.group(1)).isEqualTo(maskingFunction.apply("Armeria=awesome"));

        final Matcher matcher3 = Pattern.compile("\"cache-control\":\"(.*?)\"").matcher(responseLog);
        assertThat(matcher3.find()).isTrue();
        assertThat(matcher3.group(1)).isEqualTo("no-cache");
    }
}
