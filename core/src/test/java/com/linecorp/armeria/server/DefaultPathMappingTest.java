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

import static com.linecorp.armeria.server.PathMapping.of;
import static com.linecorp.armeria.server.PathMappingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.Test;

public class DefaultPathMappingTest {
    @Test
    public void givenMatchingPathParam_whenApply_thenReturns() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/{value}");
        final PathMappingResult result = ppe.apply(create("/service/hello", "foo=bar"));

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello");
        assertThat(result.query()).isEqualTo("foo=bar");

        final Map<String, String> pathParams = result.pathParams();
        assertThat(pathParams).containsOnlyKeys("value");
        assertThat(pathParams.get("value")).isEqualTo("hello");
    }

    @Test
    public void givenNoMatchingPathParam_whenApply_thenReturnsNull() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/{value}");
        final PathMappingResult result = ppe.apply(create("/service2/hello", "bar=baz"));

        assertThat(result.isPresent()).isFalse();
    }

    @Test
    public void testMultipleMatches() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/{value}/test/:value2/something");
        final PathMappingResult result = ppe.apply(create("/service/hello/test/world/something", "q=1"));

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/hello/test/world/something");
        assertThat(result.query()).isEqualTo("q=1");

        final Map<String, String> pathParams = result.pathParams();
        assertThat(pathParams).containsOnlyKeys("value", "value2");
        assertThat(pathParams.get("value")).isEqualTo("hello");
        assertThat(pathParams.get("value2")).isEqualTo("world");
    }

    @Test
    public void testNumericPathParamNames() {
        final DefaultPathMapping m = new DefaultPathMapping("/{0}/{1}/{2}");
        assertThat(m.paramNames()).containsExactlyInAnyOrder("0", "1", "2");
        assertThat(m.apply(create("/alice/bob/charlie")).pathParams())
                .containsEntry("0", "alice")
                .containsEntry("1", "bob")
                .containsEntry("2", "charlie")
                .hasSize(3);
    }

    @Test
    public void testVariables() throws Exception {
        final DefaultPathMapping ppe =
                new DefaultPathMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.paramNames()).containsExactlyInAnyOrder("value", "value2", "value3");
    }

    @Test
    public void testSkeleton() throws Exception {
        final DefaultPathMapping ppe =
                new DefaultPathMapping("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.skeleton()).isEqualTo("/service/:/test/:/something/:");
    }

    @Test
    public void testInvalidPattern() throws Exception {
        assertThatThrownBy(() -> new DefaultPathMapping("/service/{value}/test/{value2"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new DefaultPathMapping("/service/:value/test/value2}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testEmptyPattern() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/value/test/value2");
        final PathMappingResult result = ppe.apply(create("/service/value/test/value2"));

        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/service/value/test/value2");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    public void testToString() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/{value}/test/:value2");
        assertThat(ppe).hasToString("/service/{value}/test/:value2");
    }

    @Test
    public void testHashcodeAndEquals() throws Exception {
        final DefaultPathMapping ppe = new DefaultPathMapping("/service/:value/test/:value2");
        final DefaultPathMapping ppe2 = new DefaultPathMapping("/service/{value}/test/{value2}");
        final DefaultPathMapping ppe3 = new DefaultPathMapping("/service/{value}/test/{value3}");

        assertThat(ppe).isEqualTo(ppe2);
        assertThat(ppe).isNotEqualTo(ppe3);
        assertThat(ppe2).isNotEqualTo(ppe3);

        assertThat(ppe.hashCode()).isEqualTo(ppe2.hashCode());
        assertThat(ppe.hashCode()).isNotEqualTo(ppe3.hashCode());
        assertThat(ppe2.hashCode()).isNotEqualTo(ppe3.hashCode());
    }

    @Test
    public void testLoggerName() {
        assertThat(of("/service/{value}").loggerName()).isEqualTo("service._value_");
    }

    @Test
    public void testMetricName() {
        assertThat(of("/service/{value}").meterTag()).isEqualTo("/service/{value}");
    }
}
