/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.protobuf.BoolValue;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.xds.client.endpoint.RouteEntryMatcher.HeaderMatcherImpl;
import com.linecorp.armeria.xds.client.endpoint.RouteEntryMatcher.QueryParamsMatcherImpl;

import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.Int64Range;

class RouteEntryMatcherTest {

    /**
     * Provides test cases for header matching.
     */
    private static Stream<Arguments> headerMatch_args() {
        return Stream.of(
                // Present match with existing header
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setPresentMatch(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // Present match with missing header
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setPresentMatch(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/"),
                        false
                ),
                // StringMatch with exact match successful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("value-b")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-b"),
                        true
                ),
                // StringMatch with exact match unsuccessful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("value-b")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "different-value"),
                        false
                ),
                // StringMatch with prefix match successful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setPrefix("prefix-")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "prefix-value"),
                        true
                ),
                // StringMatch with prefix match unsuccessful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setPrefix("prefix-")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-no-prefix"),
                        false
                ),
                // StringMatch with suffix match successful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setSuffix("-suffix")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-suffix"),
                        true
                ),
                // StringMatch with suffix match unsuccessful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setSuffix("-suffix")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "no-suffix-here"),
                        false
                ),
                // StringMatch with contains match successful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setContains("middle")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "start-middle-end"),
                        true
                ),
                // StringMatch with contains match unsuccessful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setContains("middle")
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "no-match-here"),
                        false
                ),
                // StringMatch with regex match successful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(
                                             StringMatcher.newBuilder()
                                                          .setSafeRegex(RegexMatcher.newBuilder()
                                                                                    .setRegex("value-\\d+")
                                                                                    .build())
                                                          .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-123"),
                        true
                ),
                // StringMatch with regex match unsuccessful
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(
                                             StringMatcher.newBuilder()
                                                          .setSafeRegex(RegexMatcher.newBuilder()
                                                                                    .setRegex("value-\\d+")
                                                                                    .build())
                                                          .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-abc"),
                        false
                ),
                // RANGE_MATCH with value in range
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setRangeMatch(Int64Range.newBuilder()
                                                              .setStart(10)
                                                              .setEnd(20)
                                                              .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "15"),
                        true
                ),
                // RANGE_MATCH with value at start boundary
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setRangeMatch(Int64Range.newBuilder()
                                                              .setStart(10)
                                                              .setEnd(20)
                                                              .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "10"),
                        true
                ),
                // RANGE_MATCH with value at end boundary (exclusive)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setRangeMatch(Int64Range.newBuilder()
                                                              .setStart(10)
                                                              .setEnd(20)
                                                              .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "20"),
                        false
                ),
                // RANGE_MATCH with value outside range
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setRangeMatch(Int64Range.newBuilder()
                                                              .setStart(10)
                                                              .setEnd(20)
                                                              .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "5"),
                        false
                ),
                // RANGE_MATCH with non-numeric value
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setRangeMatch(Int64Range.newBuilder()
                                                              .setStart(10)
                                                              .setEnd(20)
                                                              .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "not-a-number"),
                        false
                ),
                // HEADERMATCHSPECIFIER_NOT_SET with header present
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // HEADERMATCHSPECIFIER_NOT_SET with header missing
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/"),
                        false
                ),
                // PRESENT_MATCH with treatMissingHeaderAsEmpty=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setPresentMatch(true)
                                     .setTreatMissingHeaderAsEmpty(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/"),
                        true
                ),
                // StringMatch with treatMissingHeaderAsEmpty=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("")
                                                                  .build())
                                     .setTreatMissingHeaderAsEmpty(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/"),
                        true
                ),
                // StringMatch with treatMissingHeaderAsEmpty=true but non-matching empty string
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("non-empty")
                                                                  .build())
                                     .setTreatMissingHeaderAsEmpty(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/"),
                        false
                ),
                // StringMatch with invertMatch=true (successful becomes unsuccessful)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("value-a")
                                                                  .build())
                                     .setInvertMatch(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        false
                ),
                // StringMatch with invertMatch=true (unsuccessful becomes successful)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("value-a")
                                                                  .build())
                                     .setInvertMatch(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "different-value"),
                        true
                ),
                // PRESENT_MATCH with invertMatch=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setPresentMatch(true)
                                     .setInvertMatch(true)
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        false
                ),
                // StringMatch with ignoreCase=true (case insensitive comparison)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("VALUE-A")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // StringMatch with ignoreCase=false (case sensitive comparison)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("VALUE-A")
                                                                  .setIgnoreCase(false)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        false
                ),
                // StringMatch with prefix and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setPrefix("VALUE-")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // StringMatch with suffix and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setSuffix("-A")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // StringMatch with contains and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setContains("ALUE")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-a"),
                        true
                ),
                // Multiple headers with the same name are concatenated with a comma before matching
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("multi-header")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setExact("value1,value2,value3")
                                                                  .build())
                                     .build(),
                        RequestHeaders.builder(HttpMethod.GET, "/")
                                      .add("multi-header", "value1")
                                      .add("multi-header", "value2")
                                      .add("multi-header", "value3")
                                      .build(),
                        true
                ),
                // Multiple headers with the same name are concatenated with a comma before partial matching
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("multi-header")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setContains("value2")
                                                                  .build())
                                     .build(),
                        RequestHeaders.builder(HttpMethod.GET, "/")
                                      .add("multi-header", "value1")
                                      .add("multi-header", "value2")
                                      .add("multi-header", "value3")
                                      .build(),
                        true
                ),
                // Multiple headers with the same name are concatenated with a comma before regex matching
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("multi-header")
                                     .setStringMatch(
                                             StringMatcher.newBuilder()
                                                          .setSafeRegex(
                                                                  RegexMatcher.newBuilder()
                                                                              .setRegex("value1,.*,value3")
                                                                              .build())
                                                          .build())
                                     .build(),
                        RequestHeaders.builder(HttpMethod.GET, "/")
                                      .add("multi-header", "value1")
                                      .add("multi-header", "value2")
                                      .add("multi-header", "value3")
                                      .build(),
                        true
                ),
                // Negative case for prefix and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setPrefix("VALUE-")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "wrong-value"),
                        false
                ),
                // Negative case for suffix and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setSuffix("-A")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "value-b"),
                        false
                ),
                // Negative case for contains and ignoreCase=true
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setStringMatch(StringMatcher.newBuilder()
                                                                  .setContains("ALUE")
                                                                  .setIgnoreCase(true)
                                                                  .build())
                                     .build(),
                        RequestHeaders.of(HttpMethod.GET, "/", "header-a", "no-match"),
                        false
                )
        );
    }

    /**
     * Tests header matching functionality.
     */
    @ParameterizedTest
    @MethodSource("headerMatch_args")
    void headerMatch(HeaderMatcher headerMatcher, RequestHeaders requestHeaders, boolean expectedResult) {
        final HeaderMatcherImpl matcher = new HeaderMatcherImpl(headerMatcher);
        assertThat(matcher.matches(requestHeaders)).isEqualTo(expectedResult);
    }

    /**
     * Provides test cases for path matching.
     */
    private static Stream<Arguments> pathMatch_args() {
        return Stream.of(
                // PREFIX tests
                Arguments.of(
                        "/api/users/123",
                        RouteMatch.newBuilder()
                                  .setPrefix("/api")
                                  .build(),
                        true
                ),
                Arguments.of(
                        "/users/123",
                        RouteMatch.newBuilder()
                                  .setPrefix("/api")
                                  .build(),
                        false
                ),
                // PREFIX with ignoreCase=true
                Arguments.of(
                        "/API/users/123",
                        RouteMatch.newBuilder()
                                  .setPrefix("/api")
                                  .setCaseSensitive(BoolValue.newBuilder().setValue(false).build())
                                  .build(),
                        true
                ),

                // EXACT PATH tests
                Arguments.of(
                        "/api/users/123",
                        RouteMatch.newBuilder()
                                  .setPath("/api/users/123")
                                  .build(),
                        true
                ),
                Arguments.of(
                        "/api/users/123",
                        RouteMatch.newBuilder()
                                  .setPath("/api/users/456")
                                  .build(),
                        false
                ),
                // EXACT PATH with ignoreCase=true
                Arguments.of(
                        "/API/users/123",
                        RouteMatch.newBuilder()
                                  .setPath("/api/users/123")
                                  .setCaseSensitive(BoolValue.newBuilder().setValue(false).build())
                                  .build(),
                        true
                ),

                // REGEX PATH tests
                Arguments.of(
                        "/api/users/123",
                        RouteMatch.newBuilder()
                                  .setSafeRegex(RegexMatcher.newBuilder()
                                                            .setRegex("/api/users/\\d+")
                                                            .build())
                                  .build(),
                        true
                ),
                Arguments.of(
                        "/api/users/abc",
                        RouteMatch.newBuilder()
                                  .setSafeRegex(RegexMatcher.newBuilder()
                                                            .setRegex("/api/users/\\d+")
                                                            .build())
                                  .build(),
                        false
                ),

                // PATH_SEPARATED_PREFIX tests
                Arguments.of(
                        "/api",
                        RouteMatch.newBuilder()
                                  .setPathSeparatedPrefix("/api")
                                  .build(),
                        true
                ),
                Arguments.of(
                        "/api/users",
                        RouteMatch.newBuilder()
                                  .setPathSeparatedPrefix("/api")
                                  .build(),
                        true
                ),
                Arguments.of(
                        "/api2",
                        RouteMatch.newBuilder()
                                  .setPathSeparatedPrefix("/api")
                                  .build(),
                        false
                ),
                // PATH_SEPARATED_PREFIX with ignoreCase=true
                Arguments.of(
                        "/API/users",
                        RouteMatch.newBuilder()
                                  .setPathSeparatedPrefix("/api")
                                  .setCaseSensitive(BoolValue.newBuilder().setValue(false).build())
                                  .build(),
                        true
                )
        );
    }

    /**
     * Tests path matching functionality.
     */
    @ParameterizedTest
    @MethodSource("pathMatch_args")
    void pathMatch(String path, RouteMatch routeMatch, boolean expectedResult) {
        // Create a client request context with the test path
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path));
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        // Create the PathMatcherImpl directly without reflection
        final RouteEntryMatcher.PathMatcherImpl pathMatcher = new RouteEntryMatcher.PathMatcherImpl(routeMatch);

        // Call the match method to test if the path matches
        assertThat(pathMatcher.match(ctx)).isEqualTo(expectedResult);
    }

    /**
     * Provides test cases for query parameter matching.
     */
    private static Stream<Arguments> queryMatch_args() {
        return Stream.of(
                // PRESENT_MATCH with existing parameter
                Arguments.of(
                        "param=value",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setPresentMatch(true)
                                             .build(),
                        true
                ),
                // PRESENT_MATCH with missing parameter
                Arguments.of(
                        "other=value",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setPresentMatch(true)
                                             .build(),
                        false
                ),
                // STRING_MATCH with exact match
                Arguments.of(
                        "param=value",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setExact("value")
                                                                          .build())
                                             .build(),
                        true
                ),
                // STRING_MATCH with non-matching value
                Arguments.of(
                        "param=different",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setExact("value")
                                                                          .build())
                                             .build(),
                        false
                ),
                // STRING_MATCH with prefix match
                Arguments.of(
                        "param=prefix-value",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setPrefix("prefix-")
                                                                          .build())
                                             .build(),
                        true
                ),
                // STRING_MATCH with non-matching prefix
                Arguments.of(
                        "param=value-without-prefix",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setPrefix("prefix-")
                                                                          .build())
                                             .build(),
                        false
                ),
                // Multiple query parameters
                Arguments.of(
                        "param1=value1&param2=value2&param3=value3",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param2")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setExact("value2")
                                                                          .build())
                                             .build(),
                        true
                ),
                // URL-encoded parameters
                Arguments.of(
                        "param=hello%20world",
                        QueryParameterMatcher.newBuilder()
                                             .setName("param")
                                             .setStringMatch(StringMatcher.newBuilder()
                                                                          .setExact("hello world")
                                                                          .build())
                                             .build(),
                        true
                )
        );
    }

    /**
     * Tests query parameter matching directly by creating QueryParams and testing against matcher.
     */
    @ParameterizedTest
    @MethodSource("queryMatch_args")
    @DisplayName("Test query parameter matching")
    void queryMatch(String queryString, QueryParameterMatcher queryMatcher, boolean expectedResult) {
        // Parse the QueryParams from the query string
        final QueryParams queryParams = QueryParams.fromQueryString(queryString);

        // Test the QueryParameterMatcher using the QueryParamsMatcherImpl class
        final QueryParamsMatcherImpl matcher = new QueryParamsMatcherImpl(queryMatcher);
        assertThat(matcher.matches(queryParams)).isEqualTo(expectedResult);
    }

    /**
     * Tests isGrpcRequest method with different content types.
     */
    @Test
    @DisplayName("Test isGrpcRequest method with various content types")
    void isGrpcRequestTest() {
        // Test with null request
        assertThat(XdsCommonUtil.isGrpcRequest(null)).isFalse();

        // Test with request that has no content type header
        final HttpRequest requestWithoutContentType =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/api"));
        assertThat(XdsCommonUtil.isGrpcRequest(requestWithoutContentType)).isFalse();

        // Test with exact "grpc" subtype
        final HttpRequest grpcRequest =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/api",
                                                 HttpHeaderNames.CONTENT_TYPE, "application/grpc"));
        assertThat(XdsCommonUtil.isGrpcRequest(grpcRequest)).isTrue();

        // Test with "grpc+" prefix subtype
        final HttpRequest grpcPlusRequest =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/api",
                                                 HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto"));
        assertThat(XdsCommonUtil.isGrpcRequest(grpcPlusRequest)).isTrue();

        // Test with non-grpc subtype that looks similar (grpc-web)
        final HttpRequest nonGrpcRequest =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/api",
                                                 HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"));
        assertThat(XdsCommonUtil.isGrpcRequest(nonGrpcRequest)).isFalse();
    }

    private static Stream<Arguments> deprecatedHeaderMatcher_args() {
        return Stream.of(
                // EXACT_MATCH (deprecated)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setExactMatch("value-a")
                                     .build()
                ),
                // PREFIX_MATCH (deprecated)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setPrefixMatch("prefix-")
                                     .build()
                ),
                // SUFFIX_MATCH (deprecated)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setSuffixMatch("-suffix")
                                     .build()
                ),
                // CONTAINS_MATCH (deprecated)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setContainsMatch("contains")
                                     .build()
                ),
                // SAFE_REGEX_MATCH (deprecated)
                Arguments.of(
                        HeaderMatcher.newBuilder()
                                     .setName("header-a")
                                     .setSafeRegexMatch(RegexMatcher.newBuilder()
                                                                    .setRegex("value-\\d+")
                                                                    .build())
                                     .build()
                )
        );
    }

    /**
     * Tests that deprecated header matcher values throw IllegalArgumentException.
     */
    @ParameterizedTest
    @MethodSource("deprecatedHeaderMatcher_args")
    @DisplayName("Test that deprecated HeaderMatcher values throw IllegalArgumentException")
    void deprecatedHeaderMatcher(HeaderMatcher headerMatcher) {
        assertThatThrownBy(() -> new HeaderMatcherImpl(headerMatcher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Using deprecated field")
                .hasMessageContaining("Use 'STRING_MATCH' instead");
    }
}
