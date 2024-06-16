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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.testng.util.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;

class RouteTest {

    private static final String PATH = "/test";

    private static final RequestTarget REQ_TARGET = RequestTarget.forServer(PATH);

    @Test
    void route() {
        Route route;
        Route routeWithPrefix;

        route = Route.builder().path("exact:/foo:bar/biz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo:bar/biz", "/foo:bar/biz");

        route = Route.builder().path("/foo").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo", "/foo");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/foo", "/prefix/foo");

        route = Route.builder().path("/foo:verb").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo:verb", "/foo:verb");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/foo:verb", "/prefix/foo:verb");

        route = Route.builder().path("/foo/{bar}").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/foo/\0", "/foo/\0");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/foo/\0", "/prefix/foo/\0");

        route = Route.builder().path("/foo/{bar:biz}").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/foo/\0", "/foo/\0");
        assertThat(route.paramNames()).hasSize(1);
        assertThat(route.paramNames()).containsExactly("bar:biz");

        route = Route.builder().path("/bar/:baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/\0", "/bar/\0");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/bar/\0", "/prefix/bar/\0");

        route = Route.builder().path("/bar/:baz:").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/\0", "/bar/\0");
        assertThat(route.paramNames()).hasSize(1);
        assertThat(route.paramNames()).containsExactly("baz:");

        route = Route.builder().path("/bar/:baz:verb").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/\0", "/bar/\0");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/bar/\0", "/prefix/bar/\0");

        route = Route.builder().path("/bar/:baz:foo:verb").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/\0", "/bar/\0");
        assertThat(route.paramNames()).hasSize(1);
        assertThat(route.paramNames()).containsExactly("baz:foo:verb");

        route = Route.builder().path("exact:/:foo/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/:foo/bar", "/:foo/bar");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/:foo/bar", "/prefix/:foo/bar");

        route = Route.builder().path("exact:/:foo/bar:verb").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/:foo/bar:verb", "/:foo/bar:verb");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/:foo/bar:verb", "/prefix/:foo/bar:verb");

        route = Route.builder().path("exact:/foo:bar/biz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo:bar/biz", "/foo:bar/biz");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/foo:bar/biz", "/prefix/foo:bar/biz");

        route = Route.builder().path("prefix:/").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/", "/*");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/", "/prefix/*");

        route = Route.builder().path("prefix:/bar/baz").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/bar/baz/", "/bar/baz/*");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/bar/baz/", "/prefix/bar/baz/*");

        route = Route.builder().path("glob:/foo/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(routeWithPrefix.paths()).containsExactly("/prefix/foo/bar", "/prefix/foo/bar");

        route = Route.builder().path("glob:/home/*/files/**").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/home/([^/]+)/files/(.*)$", "/home/*/files/**");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(routeWithPrefix.paths()).containsExactly("^/prefix/home/([^/]+)/files/(.*)$",
                                                            "/prefix/home/*/files/**");

        route = Route.builder().path("glob:foo").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/(?:.+/)?foo$", "/**/foo");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(routeWithPrefix.paths()).containsExactly("^/prefix/(?:.+/)?foo$", "/prefix/**/foo");

        route = Route.builder().path("regex:^/files/(?<filePath>.*)$").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/files/(?<filePath>.*)$");
        routeWithPrefix = route.withPrefix("/prefix");
        assertThat(routeWithPrefix.pathType()).isSameAs(RoutePathType.REGEX_WITH_PREFIX);
        assertThat(routeWithPrefix.paths()).containsExactly("^/files/(?<filePath>.*)$", "/prefix/");
    }

    @Test
    void routePathWithPrefix() {
        Route route;

        route = Route.builder().path("/foo", "/bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        assertThat(route.patternString()).isEqualTo("/foo/bar");

        route = Route.builder().path("/foo", "/bar:verb").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar:verb", "/foo/bar:verb");
        assertThat(route.patternString()).isEqualTo("/foo/bar:verb");

        route = Route.builder().path("/foo", "/bar/{baz}").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/foo/bar/\0", "/foo/bar/\0");
        assertThat(route.patternString()).isEqualTo("/foo/bar/:baz");

        route = Route.builder().path("/bar", "/baz/:qux").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.PARAMETERIZED);
        assertThat(route.paths()).containsExactly("/bar/baz/\0", "/bar/baz/\0");
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
        assertThatThrownBy(() -> Route.builder().path("/foo/{bar}:verb1:verb2")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void notAllowSemicolon() {
        assertThatThrownBy(() -> Route.builder().path("/foo;")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> Route.builder().path("/foo/{bar};")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> Route.builder().path("/bar/:baz;")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> Route.builder().path("exact:/:foo/bar;")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> Route.builder().path("prefix:/bar/baz;")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void testHeader() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.GET, HttpMethod.POST)
                                 .build();

        final RoutingResult getResult = route.apply(withMethod(HttpMethod.GET), false);
        final RoutingResult postResult = route.apply(withMethod(HttpMethod.POST), false);
        assertThat(getResult.isPresent()).isTrue();
        assertThat(postResult.isPresent()).isTrue();

        assertThat(route.apply(withMethod(HttpMethod.PUT), false).isPresent()).isFalse();
        assertThat(route.apply(withMethod(HttpMethod.DELETE), false).isPresent()).isFalse();
    }

    @Test
    void testConsumeType() {
        final Route route = Route.builder()
                                 .path(PATH)
                                 .methods(HttpMethod.POST)
                                 .consumes(JSON_UTF_8)
                                 .build();

        assertThat(route.apply(withConsumeType(HttpMethod.POST, JSON_UTF_8), false).isPresent()).isTrue();
        assertThat(route.apply(withConsumeType(HttpMethod.POST, MediaType.create("application", "json")), false)
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

        RoutingResult getResult = route.apply(withMethod(HttpMethod.GET), false);
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
        getResult = route.apply(withMethod(HttpMethod.GET), false);
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

        getResult = route.apply(withMethod(HttpMethod.GET), false);
        assertThat(getResult.isPresent()).isTrue();

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "*/*"), false);
        assertThat(getResult.isPresent()).isTrue();
        // When the Route has empty producibleTypes, the result has the highest score.
        assertThat(getResult.hasHighestScore()).isTrue();
        assertThat(getResult.negotiatedResponseMediaType()).isSameAs(JSON_UTF_8);

        getResult = route.apply(withAcceptHeader(HttpMethod.GET, "text/plain"), false);
        assertThat(getResult.isPresent()).isFalse();
    }

    @Test
    void testRouteExclusion() {
        Route route = Route.builder().pathPrefix("/foo")
                           .exclude(Route.builder().pathPrefix("/foo/bar").build())
                           .build();
        assertThat(route.apply(withPath("/foo/baz"), false).isPresent()).isTrue();
        // PrefixPathMapping will automatically add '/' to the end of the given path prefix.
        assertThat(route.apply(withPath("/foo/bar"), false).isPresent()).isTrue();
        assertThat(route.apply(withPath("/foo/bar/"), false).isPresent()).isFalse();

        route = Route.builder().pathPrefix("/foo")
                     .exclude(Route.builder().exact("/foo/bar").build())
                     .build();
        assertThat(route.apply(withPath("/foo/baz"), false).isPresent()).isTrue();
        assertThat(route.apply(withPath("/foo/bar"), false).isPresent()).isFalse();
        assertThat(route.apply(withPath("/foo/bar/"), false).isPresent()).isTrue();

        route = Route.builder().pathPrefix("/foo")
                     .exclude("regex:^/foo/(bar|baz)$") // Use shortcut.
                     .build();
        assertThat(route.apply(withPath("/foo/baz"), false).isPresent()).isFalse();
        assertThat(route.apply(withPath("/foo/bar"), false).isPresent()).isFalse();
        assertThat(route.apply(withPath("/foo/bar/"), false).isPresent()).isTrue();

        route = Route.builder().pathPrefix("/foo")
                     .exclude("glob:/foo/bar/**")   // Use shortcut.
                     .build();
        assertThat(route.apply(withPath("/foo/baz"), false).isPresent()).isTrue();
        assertThat(route.apply(withPath("/foo/bar"), false).isPresent()).isTrue();
        assertThat(route.apply(withPath("/foo/bar/"), false).isPresent()).isFalse();
        assertThat(route.apply(withPath("/foo/bar/baz"), false).isPresent()).isFalse();
    }

    @Test
    void testRouteExclusionIsEvaluatedAtLast() {
        final Route route = Route.builder()
                                 .pathPrefix(PATH)
                                 .methods(HttpMethod.GET, HttpMethod.POST)
                                 .consumes(MediaType.JSON)
                                 .produces(MediaType.JSON)
                                 .matchesHeaders("x-custom-header", Strings::isNotNullAndNotEmpty)
                                 .matchesParams("custom-param", Strings::isNotNullAndNotEmpty)
                                 .exclude(Route.builder().exact(PATH + "/excluded").build())
                                 .build();

        // Make sure that RoutingResult.empty() and RoutingResult.excluded() are different.
        assertThat(RoutingResult.empty()).isNotEqualTo(RoutingResult.excluded());

        RoutingContext ctx = withRequestHeaders(RequestHeaders.of(HttpMethod.PUT, PATH + "/bar"));
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.empty());
        assertThat(ctx.deferredStatusException().httpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ctx = withRequestHeaders(RequestHeaders.builder()
                                               .method(HttpMethod.POST)
                                               .path(PATH + "/bar")
                                               .contentType(MediaType.APPLICATION_XML_UTF_8)
                                               .build());
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.empty());
        assertThat(ctx.deferredStatusException().httpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        ctx = withRequestHeaders(
                RequestHeaders.builder()
                              .method(HttpMethod.POST)
                              .path(PATH + "/bar")
                              .add(HttpHeaderNames.ACCEPT, MediaType.APPLICATION_XML_UTF_8.toString())
                              .build());
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.empty());
        assertThat(ctx.deferredStatusException().httpStatus()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);

        // No x-custom-header exists.
        ctx = withRequestHeaders(RequestHeaders.builder()
                                               .method(HttpMethod.POST)
                                               .path(PATH + "/bar")
                                               .contentType(MediaType.JSON)
                                               .add(HttpHeaderNames.ACCEPT, "*/*")
                                               .build());
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.empty());
        assertThat(ctx.deferredStatusException()).isNull();

        // No custom-param exists.
        ctx = withRequestHeaders(RequestHeaders.builder()
                                               .method(HttpMethod.POST)
                                               .path(PATH + "/bar")
                                               .contentType(MediaType.JSON)
                                               .add(HttpHeaderNames.ACCEPT, "*/*")
                                               .add("x-custom-header", "yes")
                                               .build());
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.empty());
        assertThat(ctx.deferredStatusException()).isNull();

        // All conditions are met.
        ctx = withRequestHeaders(RequestHeaders.builder()
                                               .method(HttpMethod.POST)
                                               .path(PATH + "/bar?custom-param=yes")
                                               .contentType(MediaType.JSON)
                                               .add(HttpHeaderNames.ACCEPT, "*/*")
                                               .add("x-custom-header", "yes")
                                               .build());
        assertThat(route.apply(ctx, false).isPresent()).isTrue();

        // All conditions are met but the request is matched with excludedRoute.
        ctx = withRequestHeaders(RequestHeaders.builder()
                                               .method(HttpMethod.POST)
                                               .path(PATH + "/excluded?custom-param=yes")
                                               .contentType(MediaType.JSON)
                                               .add(HttpHeaderNames.ACCEPT, "*/*")
                                               .add("x-custom-header", "yes")
                                               .build());
        // Note that DefaultRoute returns RoutingResult.EXCLUDED when the request is failed to route due to
        // 'excludedRoutes' configuration.
        assertThat(route.apply(ctx, false)).isEqualTo(RoutingResult.excluded());
        assertThat(ctx.deferredStatusException()).isNull();
    }

    @Test
    void testJsonSerialization() {
        Route route = Route.builder().pathPrefix("/foo").build();

        assertThatJson(route).node("pathType").isEqualTo("PREFIX");
        assertThatJson(route).node("patternString").isEqualTo("/foo/*");

        route = Route.builder().exact("/foo").build();

        assertThatJson(route).node("pathType").isEqualTo("EXACT");
        assertThatJson(route).node("patternString").isEqualTo("/foo");

        route = Route.builder().glob("/foo/*").build();

        assertThatJson(route).node("pathType").isEqualTo("REGEX");
        assertThatJson(route).node("patternString").isEqualTo("/foo/*");

        route = Route.builder().regex("/foo/.+").build();

        assertThatJson(route).node("pathType").isEqualTo("REGEX");
        assertThatJson(route).node("patternString").isEqualTo("/foo/.+");

        route = Route.builder().path("/foo/:id").build();

        assertThatJson(route).node("pathType").isEqualTo("PARAMETERIZED");
        assertThatJson(route).node("patternString").isEqualTo("/foo/:id");
    }

    @Test
    void prefixWithEmptySlash() {
        final Route route = Route.builder().pathPrefix("/foo").build();
        assertThat(route.withPrefix("/")).isSameAs(route);
    }

    private static RoutingContext withMethod(HttpMethod method) {
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        REQ_TARGET, RequestHeaders.of(method, PATH), RoutingStatus.OK,
                                        SessionProtocol.H2C);
    }

    private static RoutingContext withConsumeType(HttpMethod method, MediaType contentType) {
        final RequestHeaders headers = RequestHeaders.of(method, PATH,
                                                         HttpHeaderNames.CONTENT_TYPE, contentType);
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        REQ_TARGET, headers, RoutingStatus.OK, SessionProtocol.H2C);
    }

    private static RoutingContext withAcceptHeader(HttpMethod method, String acceptHeader) {
        final RequestHeaders headers = RequestHeaders.of(method, PATH,
                                                         HttpHeaderNames.ACCEPT, acceptHeader);
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        REQ_TARGET, headers, RoutingStatus.OK, SessionProtocol.H2C);
    }

    private static RoutingContext withPath(String path) {
        final RequestTarget reqTarget = RequestTarget.forServer(path);
        assertThat(reqTarget).isNotNull();

        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        reqTarget, RequestHeaders.of(HttpMethod.GET, path),
                                        RoutingStatus.OK, SessionProtocol.H2C);
    }

    private static RoutingContext withRequestHeaders(RequestHeaders headers) {
        final RequestTarget reqTarget = RequestTarget.forServer(headers.path());
        assertThat(reqTarget).isNotNull();
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        reqTarget, headers, RoutingStatus.OK, SessionProtocol.H2C);
    }
}
