/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.MediaType.ANY_TYPE;
import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static com.linecorp.armeria.server.PathMappingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

public class HttpHeaderPathMappingTest {

    private static final String PATH = "/test";

    @Test
    public void testLoggerName() {
        HttpHeaderPathMapping mapping;
        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH), ImmutableSet.of(HttpMethod.GET),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8),
                                            ImmutableList.of(JSON_UTF_8));
        assertThat(mapping.loggerName())
                .isEqualTo("test.GET.consumes.text_plain.produces.application_json");

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH), ImmutableSet.of(HttpMethod.GET),
                                            ImmutableList.of(),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8, JSON_UTF_8));
        assertThat(mapping.loggerName())
                .isEqualTo("test.GET.produces.text_plain.application_json");

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH),
                                            ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8, JSON_UTF_8),
                                            ImmutableList.of());
        assertThat(mapping.loggerName())
                .isEqualTo("test.GET_POST.consumes.text_plain.application_json");
    }

    @Test
    public void testMetricName() {
        HttpHeaderPathMapping mapping;
        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH), ImmutableSet.of(HttpMethod.GET),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8),
                                            ImmutableList.of(JSON_UTF_8));
        assertThat(mapping.meterTag())
                .isEqualTo("exact:/test,methods:GET,consumes:text/plain,produces:application/json");

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH), ImmutableSet.of(HttpMethod.GET),
                                            ImmutableList.of(),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8, JSON_UTF_8));
        assertThat(mapping.meterTag())
                .isEqualTo("exact:/test,methods:GET,produces:text/plain,application/json");

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH),
                                            ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                            ImmutableList.of(PLAIN_TEXT_UTF_8, JSON_UTF_8),
                                            ImmutableList.of());
        assertThat(mapping.meterTag())
                .isEqualTo("exact:/test,methods:GET,POST,consumes:text/plain,application/json");
    }

    @Test
    public void testHttpHeader() {
        final HttpHeaderPathMapping mapping =
                new HttpHeaderPathMapping(PathMapping.of(PATH),
                                          ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                          ImmutableList.of(), ImmutableList.of());

        final PathMappingResult getResult = mapping.apply(method(HttpMethod.GET));
        final PathMappingResult postResult = mapping.apply(method(HttpMethod.POST));
        assertThat(getResult.isPresent()).isTrue();
        assertThat(postResult.isPresent()).isTrue();

        assertThat(mapping.apply(method(HttpMethod.PUT)).isPresent()).isFalse();
        assertThat(mapping.apply(method(HttpMethod.DELETE)).isPresent()).isFalse();
    }

    @Test
    public void testConsumeType() {
        final HttpHeaderPathMapping mapping =
                new HttpHeaderPathMapping(PathMapping.of(PATH),
                                          ImmutableSet.of(HttpMethod.POST),
                                          ImmutableList.of(JSON_UTF_8), ImmutableList.of());

        assertThat(mapping.apply(consumeType(HttpMethod.POST, JSON_UTF_8)).isPresent()).isTrue();
        assertThat(mapping.apply(consumeType(HttpMethod.POST, MediaType.create("application", "json")))
                          .isPresent()).isFalse();
    }

    @Test
    public void testAcceptType() {
        final HttpHeaderPathMapping mapping =
                new HttpHeaderPathMapping(PathMapping.of(PATH),
                                          ImmutableSet.of(HttpMethod.GET),
                                          ImmutableList.of(), ImmutableList.of(JSON_UTF_8));

        assertThat(mapping.apply(withAcceptHeader(HttpMethod.GET, "*/*")).isPresent()).isTrue();
        assertThat(mapping.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"))
                          .isPresent()).isTrue();

        PathMappingResult result;

        result = mapping.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8;q=0.8,text/plain;q=0.9"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.score()).isEqualTo(-1);

        result = mapping.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8,text/plain;q=0.9"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.hasHighestScore()).isTrue();

        assertThat(mapping.apply(withAcceptHeader(HttpMethod.GET, "application/x-www-form-urlencoded"))
                          .isPresent()).isFalse();
    }

    @Test
    public void testAcceptType2() {
        // Empty produce types mapping.

        HttpHeaderPathMapping mapping =
                new HttpHeaderPathMapping(PathMapping.of(PATH),
                                          ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                          ImmutableList.of(), ImmutableList.of());

        PathMappingResult getResult = mapping.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"));
        assertThat(getResult.isPresent()).isFalse();

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        // When the HttpHeaderPathMapping has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isNull();

        // ANY_TYPE produceTypes mapping.

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH),
                                            ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                            ImmutableList.of(), ImmutableList.of(ANY_TYPE));

        getResult = mapping.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"));
        assertThat(getResult.isPresent()).isFalse();

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        // When the HttpHeaderPathMapping has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isNull();

        // Not empty produceTypes mapping.

        mapping = new HttpHeaderPathMapping(PathMapping.of(PATH),
                                            ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                            ImmutableList.of(), ImmutableList.of(JSON_UTF_8));

        getResult = mapping.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        // When the HttpHeaderPathMapping has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isSameAs(JSON_UTF_8);

        getResult = mapping.apply(withAcceptHeader(HttpMethod.GET, "text/plain"));
        assertThat(getResult.isPresent()).isFalse();
    }

    private static PathMappingContext method(HttpMethod method) {
        return DefaultPathMappingContext.of(virtualHost(), "example.com",
                                            PATH, null, HttpHeaders.of(method, PATH));
    }

    private static PathMappingContext consumeType(HttpMethod method, MediaType contentType) {
        final HttpHeaders headers = HttpHeaders.of(method, PATH);
        headers.contentType(contentType);
        return DefaultPathMappingContext.of(virtualHost(), "example.com",
                                            PATH, null, headers);
    }

    private static PathMappingContext withAcceptHeader(HttpMethod method, String acceptHeader) {
        final HttpHeaders headers = HttpHeaders.of(method, PATH);
        headers.add(HttpHeaderNames.ACCEPT, acceptHeader);
        return DefaultPathMappingContext.of(virtualHost(), "example.com", PATH, null, headers);
    }
}
