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
import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

class RouteTest {

    private static final String PATH = "/test";

    @Test
    void routePath() {
        Route route;

        route = Route.builder().path("/foo").build();
        assertThat(route.exactPath()).contains("/foo");

        route = Route.builder().path("/foo/{bar}").build();
        assertThat(route.triePath()).contains("/foo/:");

        route = Route.builder().path("/bar/:baz").build();
        assertThat(route.triePath()).contains("/bar/:");

        route = Route.builder().path("exact:/:foo/bar").build();
        assertThat(route.exactPath()).contains("/:foo/bar");

        route = Route.builder().path("prefix:/").build();
        assertThat(route.triePath()).contains("/*");

        route = Route.builder().path("prefix:/bar/baz").build();
        assertThat(route.prefix()).contains("/bar/baz/");

        route = Route.builder().path("glob:/foo/bar").build();
        assertThat(route.exactPath()).contains("/foo/bar");

        route = Route.builder().path("glob:/home/*/files/**").build();
        assertThat(route.regex()).contains("^/home/([^/]+)/files/(.*)$");

        route = Route.builder().path("glob:foo").build();
        assertThat(route.regex()).contains("^/(?:.+/)?foo$");

        route = Route.builder().path("regex:^/files/(?<filePath>.*)$").build();
        assertThat(route.regex()).contains("^/files/(?<filePath>.*)$");
    }

    @Test
    void invalidRoutePath() {
        assertThrows(IllegalArgumentException.class,
                     () -> Route.builder().path("foo"));

        assertThrows(IllegalArgumentException.class,
                     () -> Route.builder().path("foo:/bar"));
    }

    @Test
    void testLoggerName() {
        Route route;
        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET)
                     .consumes(PLAIN_TEXT_UTF_8)
                     .produces(JSON_UTF_8)
                     .build();
        assertThat(route.loggerName())
                .isEqualTo("test.GET.consumes.text_plain.produces.application_json");

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET)
                     .produces(PLAIN_TEXT_UTF_8, JSON_UTF_8)
                     .build();
        assertThat(route.loggerName())
                .isEqualTo("test.GET.produces.text_plain.application_json");

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .consumes(PLAIN_TEXT_UTF_8, JSON_UTF_8)
                     .build();
        assertThat(route.loggerName())
                .isEqualTo("test.GET_POST.consumes.text_plain.application_json");
    }

    @Test
    void testMetricName() {
        Route route;
        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET)
                     .consumes(PLAIN_TEXT_UTF_8)
                     .produces(JSON_UTF_8)
                     .build();
        assertThat(route.meterTag())
                .isEqualTo("exact:/test,methods:GET,consumes:text/plain,produces:application/json");

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET)
                     .produces(PLAIN_TEXT_UTF_8, JSON_UTF_8)
                     .build();
        assertThat(route.meterTag())
                .isEqualTo("exact:/test,methods:GET,produces:text/plain,application/json");

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .consumes(PLAIN_TEXT_UTF_8, JSON_UTF_8)
                     .build();
        assertThat(route.meterTag())
                .isEqualTo("exact:/test,methods:GET,POST,consumes:text/plain,application/json");
    }

    @Test
    void testHttpHeader() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.GET, HttpMethod.POST)
                                 .build();

        final RoutingResult getResult = route.apply(method(HttpMethod.GET));
        final RoutingResult postResult = route.apply(method(HttpMethod.POST));
        assertThat(getResult.isPresent()).isTrue();
        assertThat(postResult.isPresent()).isTrue();

        assertThat(route.apply(method(HttpMethod.PUT)).isPresent()).isFalse();
        assertThat(route.apply(method(HttpMethod.DELETE)).isPresent()).isFalse();
    }

    @Test
    void testConsumeType() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.POST)
                                 .consumes(JSON_UTF_8)
                                 .build();

        assertThat(route.apply(consumeType(HttpMethod.POST, JSON_UTF_8)).isPresent()).isTrue();
        assertThat(route.apply(consumeType(HttpMethod.POST, MediaType.create("application", "json")))
                        .isPresent()).isFalse();
    }

    @Test
    void testAcceptType() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.GET)
                                 .produces(JSON_UTF_8)
                                 .build();

        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "*/*")).isPresent()).isTrue();
        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"))
                        .isPresent()).isTrue();

        RoutingResult result;

        result = route.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8;q=0.8,text/plain;q=0.9"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.score()).isEqualTo(-1);

        result = route.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8,text/plain;q=0.9"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.hasHighestScore()).isTrue();

        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "application/x-www-form-urlencoded"))
                        .isPresent()).isFalse();
    }

    @Test
    void testAcceptType2() {
        // Empty produce types route.
        Route route = Route.builder()
                           .path(PATH)
                           .methods(HttpMethod.GET, HttpMethod.POST)
                           .build();

        RoutingResult getResult = route.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"));
        assertThat(getResult.isPresent()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isNull();

        // ANY_TYPE produceTypes route.

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .produces(ANY_TYPE)
                     .build();
        getResult = route.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"));
        assertThat(getResult.isPresent()).isFalse();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        // When the Route has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isNull();

        // Not empty produceTypes route.

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .produces(JSON_UTF_8)
                     .build();

        getResult = route.apply(method(HttpMethod.GET));
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"));
        assertThat(getResult.isPresent()).isTrue();
        // When the Route has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isSameAs(JSON_UTF_8);

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "text/plain"));
        assertThat(getResult.isPresent()).isFalse();
    }

    private static RoutingContext method(HttpMethod method) {
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        PATH, null, RequestHeaders.of(method, PATH), false);
    }

    private static RoutingContext consumeType(HttpMethod method, MediaType contentType) {
        final RequestHeaders headers = RequestHeaders.of(method, PATH,
                                                         HttpHeaderNames.CONTENT_TYPE, contentType);
        return DefaultRoutingContext.of(virtualHost(), "example.com", PATH, null, headers, false);
    }

    private static RoutingContext withAcceptHeader(HttpMethod method, String acceptHeader) {
        final RequestHeaders headers = RequestHeaders.of(method, PATH,
                                                         HttpHeaderNames.ACCEPT, acceptHeader);
        return DefaultRoutingContext.of(virtualHost(), "example.com", PATH, null, headers, false);
    }
}
