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

class PathParamMappingTest {

    @Test
    void givenMatchingPathParam_whenApply_thenReturns() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/{value}");
        final RoutingResult result = ppe.apply(create("/service/hello", "foo=bar")).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello");
        assertThat(result.query()).isEqualTo("foo=bar");

        final Map<String, String> pathParams = result.pathParams();
        assertThat(pathParams).containsOnlyKeys("value");
        assertThat(pathParams.get("value")).isEqualTo("hello");
    }

    @Test
    void givenNoMatchingPathParam_whenApply_thenReturnsNull() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/{value}");
        final RoutingResultBuilder builder = ppe.apply(create("/service2/hello", "bar=baz"));
        assertThat(builder).isNull();
    }

    @Test
    void testMultipleMatches() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/{value}/test/:value2/something");
        final RoutingResult result = ppe.apply(create("/service/hello/test/world/something", "q=1")).build();

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
        final PathParamMapping ppe = new PathParamMapping("/service/{value}/");
        final RoutingResult result = ppe.apply(create("/service/hello/", null)).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello/");
    }

    @Test
    void testNumericPathParamNames() {
        final PathParamMapping m = new PathParamMapping("/{0}/{1}/{2}");
        assertThat(m.paramNames()).containsExactlyInAnyOrder("0", "1", "2");
        assertThat(m.apply(create("/alice/bob/charlie")).build().pathParams())
                .containsEntry("0", "alice")
                .containsEntry("1", "bob")
                .containsEntry("2", "charlie")
                .hasSize(3);
    }

    @Test
    void utf8() {
        final PathParamMapping m = new PathParamMapping("/{foo}");
        final RoutingResult res = m.apply(create("/%C2%A2")).build();
        assertThat(res.path()).isEqualTo("/%C2%A2");
        assertThat(res.decodedPath()).isEqualTo("/¢");
        assertThat(res.pathParams()).containsEntry("foo", "¢").hasSize(1);
    }

    @Test
    void testVariables() throws Exception {
        final PathParamMapping ppe =
                new PathParamMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.paramNames()).containsExactlyInAnyOrder("value", "value2", "value3");
    }

    @Test
    void testSkeleton() throws Exception {
        final PathParamMapping ppe =
                new PathParamMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.skeleton()).isEqualTo("/service/:/test/:/something/:");
    }

    @Test
    void testInvalidPattern() throws Exception {
        assertThatThrownBy(() -> new PathParamMapping("/service/{value}/test/{value2"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PathParamMapping("/service/:value/test/value2}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEmptyPattern() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/value/test/value2");
        final RoutingResult result = ppe.apply(create("/service/value/test/value2")).build();

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/value/test/value2");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    void testToString() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/{value}/test/:value2");
        assertThat(ppe).hasToString("/service/{value}/test/:value2");
    }

    @Test
    void testHashcodeAndEquals() throws Exception {
        final PathParamMapping ppe = new PathParamMapping("/service/:value/test/:value2");
        final PathParamMapping ppe2 = new PathParamMapping("/service/{value}/test/{value2}");
        final PathParamMapping ppe3 = new PathParamMapping("/service/{value}/test/{value3}");

        assertThat(ppe).isEqualTo(ppe2);
        assertThat(ppe).isNotEqualTo(ppe3);
        assertThat(ppe2).isNotEqualTo(ppe3);

        assertThat(ppe.hashCode()).isEqualTo(ppe2.hashCode());
        assertThat(ppe.hashCode()).isNotEqualTo(ppe3.hashCode());
        assertThat(ppe2.hashCode()).isNotEqualTo(ppe3.hashCode());
    }

    @Test
    void loggerAndMetricName() {
        final PathParamMapping pathParamMapping = new PathParamMapping("/service/{value}");
        assertThat(pathParamMapping.loggerName()).isEqualTo("service._value_");
        assertThat(pathParamMapping.meterTag()).isEqualTo("/service/{value}");
    }
}
