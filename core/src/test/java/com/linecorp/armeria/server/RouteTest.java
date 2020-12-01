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
import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo", "/foo");

        route = Route.builder().path("/foo/{bar}").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/foo/:", "/foo/:");

        route = Route.builder().path("/bar/:baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/:", "/bar/:");

        route = Route.builder().path("exact:/:foo/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/:foo/bar", "/:foo/bar");

        route = Route.builder().path("prefix:/").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/", "/*");

        route = Route.builder().path("prefix:/bar/baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/bar/baz/", "/bar/baz/*");

        route = Route.builder().path("glob:/foo/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");

        route = Route.builder().path("glob:/home/*/files/**").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/home/([^/]+)/files/(.*)$", "/home/*/files/**");

        route = Route.builder().path("glob:foo").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/(?:.+/)?foo$", "/**/foo");

        route = Route.builder().path("regex:^/files/(?<filePath>.*)$").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/files/(?<filePath>.*)$");
    }

    @Test
    void routePathWithPrefix() {
        Route route;

        route = Route.builder().path("/foo", "/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        assertThat(route.patternString()).isEqualTo("/foo/bar");

        route = Route.builder().path("/foo", "/bar/{baz}").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/foo/bar/:", "/foo/bar/:");
        assertThat(route.patternString()).isEqualTo("/foo/bar/:baz");

        route = Route.builder().path("/bar", "/baz/:qux").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/baz/:", "/bar/baz/:");
        assertThat(route.patternString()).isEqualTo("/bar/baz/:qux");

        route = Route.builder().path("/foo", "exact:/:bar/baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/:bar/baz", "/foo/:bar/baz");
        assertThat(route.patternString()).isEqualTo("/foo/:bar/baz");

        route = Route.builder().path("/foo", "prefix:/").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/foo/", "/foo/*");
        assertThat(route.patternString()).isEqualTo("/foo/*");

        route = Route.builder().path("/foo", "prefix:/bar/baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/foo/bar/baz/", "/foo/bar/baz/*");
        assertThat(route.patternString()).isEqualTo("/foo/bar/baz/*");

        route = Route.builder().path("/foo", "glob:/bar/baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar/baz", "/foo/bar/baz");
        assertThat(route.patternString()).isEqualTo("/foo/bar/baz");

        route = Route.builder().path("/foo", "glob:/home/*/files/**").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/foo/home/([^/]+)/files/(.*)$", "/foo/home/*/files/**");
        assertThat(route.patternString()).isEqualTo("/foo/home/*/files/**");

        route = Route.builder().path("/foo", "glob:bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/foo/(?:.+/)?bar$", "/foo/**/bar");
        assertThat(route.patternString()).isEqualTo("/foo/**/bar");

        route = Route.builder().path("/foo", "regex:^/files/(?<filePath>.*)$").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX_WITH_PREFIX);
        assertThat(route.paths()).containsExactly("^/files/(?<filePath>.*)$", "/foo/");
        assertThat(route.patternString()).isEqualTo("/foo/^/files/(?<filePath>.*)$");
    }

    @Test
    void invalidRoutePath() {
        assertThatThrownBy(() -> Route.builder().path("foo")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Route.builder().path("foo:/bar")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testHeader() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.GET, HttpMethod.POST)
                                 .build();

        final RoutingResult getResult = route.apply(method(HttpMethod.GET), false);
        final RoutingResult postResult = route.apply(method(HttpMethod.POST), false);
        assertThat(getResult.isPresent()).isTrue();
        assertThat(postResult.isPresent()).isTrue();

        assertThat(route.apply(method(HttpMethod.PUT), false).isPresent()).isFalse();
        assertThat(route.apply(method(HttpMethod.DELETE), false).isPresent()).isFalse();
    }

    @Test
    void testConsumeType() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.POST)
                                 .consumes(JSON_UTF_8)
                                 .build();

        assertThat(route.apply(consumeType(HttpMethod.POST, JSON_UTF_8), false).isPresent()).isTrue();
        assertThat(route.apply(consumeType(HttpMethod.POST, MediaType.create("application", "json")), false)
                        .isPresent()).isFalse();
    }

    @Test
    void testAcceptType() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.GET)
                                 .produces(JSON_UTF_8)
                                 .build();

        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "*/*"), false).isPresent()).isTrue();
        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"), false)
                        .isPresent()).isTrue();

        RoutingResult result;

        result = route.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8;q=0.8,text/plain;q=0.9"),
                false);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.score()).isEqualTo(-1);

        result = route.apply(
                withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8,text/plain;q=0.9"), false);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.hasHighestScore()).isTrue();

        assertThat(route.apply(withAcceptHeader(HttpMethod.GET, "application/x-www-form-urlencoded"), false)
                        .isPresent()).isFalse();
    }

    @Test
    void testAcceptType2() {
        // Empty produce types route.
        Route route = Route.builder()
                           .path(PATH)
                           .methods(HttpMethod.GET, HttpMethod.POST)
                           .build();

        RoutingResult getResult = route.apply(method(HttpMethod.GET), false);
        assertThat(getResult.isPresent()).isTrue();
        // When there's no "Accept" header, it has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"), false);
        assertThat(getResult.isPresent()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"), false);
        assertThat(getResult.isPresent()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isNull();

        // ANY_TYPE produceTypes route.

        route = Route.builder()
                     .path(PATH)
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .produces(ANY_TYPE)
                     .build();
        getResult = route.apply(method(HttpMethod.GET), false);
        assertThat(getResult.isPresent()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "application/json;charset=UTF-8"), false);
        assertThat(getResult.isPresent()).isFalse();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"), false);
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

        getResult = route.apply(method(HttpMethod.GET), false);
        assertThat(getResult.isPresent()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"), false);
        assertThat(getResult.isPresent()).isTrue();
        // When the Route has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isSameAs(JSON_UTF_8);

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "text/plain"), false);
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
