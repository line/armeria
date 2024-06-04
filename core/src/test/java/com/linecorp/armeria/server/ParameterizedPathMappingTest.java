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

import static com.linecorp.armeria.server.RoutingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParameterizedPathMappingTest {
    @Test
    void givenMatchingPathParam_whenApply_thenReturns() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/{value}");
        final RoutingResult result = ppm.apply(create("/service/hello", "foo=bar")).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello");
        assertThat(result.query()).isEqualTo("foo=bar");

        final Map<String, String> pathParams = result.pathParams();
        assertThat(pathParams).containsOnlyKeys("value");
        assertThat(pathParams.get("value")).isEqualTo("hello");
    }

    @Test
    void givenNoMatchingPathParam_whenApply_thenReturnsNull() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/{value}");
        final RoutingResultBuilder builder = ppm.apply(create("/service2/hello", "bar=baz"));
        assertThat(builder).isNull();
    }

    @Test
    void testMultipleMatches() throws Exception {
        final ParameterizedPathMapping
                ppm = new ParameterizedPathMapping("/service/{value}/test/:value2/something");
        final RoutingResult result = ppm.apply(create("/service/hello/test/world/something", "q=1")).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello/test/world/something");
        assertThat(result.query()).isEqualTo("q=1");

        final Map<String, String> pathParams = result.pathParams();
        assertThat(pathParams).containsOnlyKeys("value", "value2");
        assertThat(pathParams.get("value")).isEqualTo("hello");
        assertThat(pathParams.get("value2")).isEqualTo("world");
    }

    @Test
    void testPathEndsWithSlash() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/{value}/");
        final RoutingResult result = ppm.apply(create("/service/hello/", null)).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello/");
    }

    @Test
    void testNumericPathParamNames() {
        final ParameterizedPathMapping m = new ParameterizedPathMapping("/{0}/{1}/{2}");
        assertThat(m.paramNames()).containsExactlyInAnyOrder("0", "1", "2");
        assertThat(m.apply(create("/alice/bob/charlie")).build().pathParams())
                .containsEntry("0", "alice")
                .containsEntry("1", "bob")
                .containsEntry("2", "charlie")
                .hasSize(3);
    }

    @Test
    void utf8() {
        final ParameterizedPathMapping m = new ParameterizedPathMapping("/{foo}");
        final RoutingResult res = m.apply(create("/%C2%A2")).build();
        assertThat(res.path()).isEqualTo("/%C2%A2");
        assertThat(res.decodedPath()).isEqualTo("/¢");
        assertThat(res.pathParams()).containsEntry("foo", "¢").hasSize(1);
    }

    @Test
    void testVariables() throws Exception {
        final ParameterizedPathMapping ppm =
                new ParameterizedPathMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppm.paramNames()).containsExactlyInAnyOrder("value", "value2", "value3");
    }

    @Test
    void testSkeleton() throws Exception {
        final ParameterizedPathMapping ppm =
                new ParameterizedPathMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppm.skeleton()).isEqualTo("/service/\0/test/\0/something/\0");

        final ParameterizedPathMapping ppm2 = new ParameterizedPathMapping("/service/{*value}");
        assertThat(ppm2.skeleton()).isEqualTo("/service/*");

        final ParameterizedPathMapping ppm3 =
                new ParameterizedPathMapping("/service/{value1}/:value2/{*value3}");
        assertThat(ppm3.skeleton()).isEqualTo("/service/\0/\0/*");
    }

    @Test
    void testInvalidPattern() throws Exception {
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{value}/test/{value2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEmptyPattern() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/value/test/value2");
        final RoutingResult result = ppm.apply(create("/service/value/test/value2")).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/value/test/value2");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    void testToString() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/{value}/test/:value2");
        assertThat(ppm).hasToString("/service/{value}/test/:value2");
    }

    @Test
    void testHashcodeAndEquals() throws Exception {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/:value/test/:value2");
        final ParameterizedPathMapping ppm2 = new ParameterizedPathMapping("/service/{value}/test/{value2}");
        final ParameterizedPathMapping ppm3 = new ParameterizedPathMapping("/service/{value}/test/{value3}");

        assertThat(ppm).isEqualTo(ppm2);
        assertThat(ppm).isNotEqualTo(ppm3);
        assertThat(ppm2).isNotEqualTo(ppm3);

        assertThat(ppm.hashCode()).isEqualTo(ppm2.hashCode());
        assertThat(ppm.hashCode()).isNotEqualTo(ppm3.hashCode());
        assertThat(ppm2.hashCode()).isNotEqualTo(ppm3.hashCode());
    }

    @Test
    void patternString() {
        final ParameterizedPathMapping pathMappingWithBrace = new ParameterizedPathMapping("/service/{value}");
        assertThat(pathMappingWithBrace.patternString()).isEqualTo("/service/:value");

        final ParameterizedPathMapping pathMappingWithColon = new ParameterizedPathMapping("/service/:value");
        assertThat(pathMappingWithColon.patternString()).isEqualTo("/service/:value");

        final ParameterizedPathMapping pathMappingWithComplexPattern =
                new ParameterizedPathMapping("/service/{value}/items/{value}/:itemId");
        assertThat(pathMappingWithComplexPattern.patternString())
                .isEqualTo("/service/:value/items/:value/:itemId");

        final ParameterizedPathMapping pathMappingWithCaptureRestPathPattern =
                new ParameterizedPathMapping("/service/{*value}");
        assertThat(pathMappingWithCaptureRestPathPattern.patternString()).isEqualTo("/service/:*value");

        final ParameterizedPathMapping pathMappingWithCaptureRestPathPattern2 =
                new ParameterizedPathMapping("/service/{value1}/:value2/{*value3}");
        assertThat(pathMappingWithCaptureRestPathPattern2.patternString())
                .isEqualTo("/service/:value1/:value2/:*value3");
    }

    @Test
    void captureRestPattern() {
        final ParameterizedPathMapping ppm = new ParameterizedPathMapping("/service/{*value}");
        final RoutingResult matchSingleParam = ppm.apply(create("/service/hello", "foo=bar")).build();
        assertThat(matchSingleParam.isPresent()).isTrue();
        assertThat(matchSingleParam.pathParams()).containsEntry("value", "hello");

        final RoutingResult matchMultiParams = ppm.apply(create("/service/foo/bar", "foo=bar")).build();
        assertThat(matchMultiParams.isPresent()).isTrue();
        assertThat(matchMultiParams.pathParams()).containsEntry("value", "foo/bar");

        final ParameterizedPathMapping ppm2 = new ParameterizedPathMapping("/service/{value1}/{*value_2}");
        final RoutingResult matchSingleParam2 = ppm2.apply(create("/service/foo/bar", "foo=bar")).build();
        assertThat(matchSingleParam2.isPresent()).isTrue();
        assertThat(matchSingleParam2.pathParams()).containsEntry("value1", "foo")
                                                  .containsEntry("value_2", "bar");

        final RoutingResult matchMultiParams2 = ppm2.apply(create("/service/foo/bar/baz", "foo=bar")).build();
        assertThat(matchMultiParams2.isPresent()).isTrue();
        assertThat(matchMultiParams2.pathParams()).containsEntry("value1", "foo")
                                                  .containsEntry("value_2", "bar/baz");

        final ParameterizedPathMapping ppm3 = new ParameterizedPathMapping("/service/:*value");
        final RoutingResult matchSingleParam3 = ppm3.apply(create("/service/hello", "foo=bar")).build();
        assertThat(matchSingleParam3.isPresent()).isTrue();
        assertThat(matchSingleParam3.pathParams()).containsEntry("value", "hello");

        final RoutingResult matchMultiParams3 = ppm3.apply(create("/service/foo/bar", "foo=bar")).build();
        assertThat(matchMultiParams3.isPresent()).isTrue();
        assertThat(matchMultiParams3.pathParams()).containsEntry("value", "foo/bar");

        final ParameterizedPathMapping ppm4 = new ParameterizedPathMapping("/service/:value1/:*value_2");
        final RoutingResult matchSingleParam4 = ppm4.apply(create("/service/foo/bar", "foo=bar")).build();
        assertThat(matchSingleParam4.isPresent()).isTrue();
        assertThat(matchSingleParam4.pathParams()).containsEntry("value1", "foo")
                                                  .containsEntry("value_2", "bar");
    }

    @Test
    void captureRestPattern_invalidPattern() {
        // "{*...}" or ":*..." must be located at the end of the path.
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{*value}/{*value2}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{*value}/foo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:*value/:*value2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:*value/foo"))
                .isInstanceOf(IllegalArgumentException.class);
        // The variable name must be at least a character of alphabet, number and underscore.
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{*}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{**}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{* }"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:*"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:**"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:* "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> colonAllowedArgs() {
        return Stream.of(
                Arguments.of("/foo:bar", "/foo:bar"),
                Arguments.of("/a:/b:asdf", "/a:/b:asdf"),
                Arguments.of("/\\:/\\:asdf:", "/:/:asdf:")
        );
    }

    @ParameterizedTest
    @MethodSource("colonAllowedArgs")
    void colonAllowed(String pathPattern, String reqPath) {
        final ParameterizedPathMapping m = new ParameterizedPathMapping(pathPattern);
        final RoutingResult res = m.apply(create(reqPath)).build();
        assertThat(res.path()).isEqualTo(reqPath);
        assertThat(res.pathParams()).isEmpty();
    }
}
