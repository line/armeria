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

import org.junit.jupiter.api.Test;

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

        assertThat(ppm.skeleton()).isEqualTo("/service/:/test/:/something/:");
    }

    @Test
    void testInvalidPattern() throws Exception {
        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/{value}/test/{value2"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ParameterizedPathMapping("/service/:value/test/value2}"))
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
    void loggerAndMetricName() {
        final ParameterizedPathMapping parameterizedPathMapping =
                new ParameterizedPathMapping("/service/{value}");
        assertThat(parameterizedPathMapping.loggerName()).isEqualTo("service._value_");
        assertThat(parameterizedPathMapping.meterTag()).isEqualTo("/service/{value}");
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
    }
}
