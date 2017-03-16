/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.Test;

public class PathParamExtractorTest {
    @Test
    public void givenMatchingPathParam_whenExtract_thenReturns() throws Exception {
        PathParamExtractor ppe = PathParamExtractor.of("/service/{value}");

        Map<String, String> values = ppe.extract("/service/hello");

        assertThat(values).hasSize(1);
        assertThat(values.get("value")).isEqualTo("hello");
    }

    @Test
    public void givenNoMatchingPathParam_whenExtract_thenReturnsEmptyMap() throws Exception {
        PathParamExtractor ppe = PathParamExtractor.of("/service/{value}");

        Map<String, String> values = ppe.extract("/service2/hello");

        assertThat(values).isEmpty();
    }

    @Test
    public void testMultipleMatch() throws Exception {
        PathParamExtractor ppe = PathParamExtractor.of("service/{value}/test/:value2/something");

        Map<String, String> values = ppe.extract("service/hello/test/world/something?q=1");

        assertThat(values).hasSize(2);
        assertThat(values.get("value")).isEqualTo("hello");
        assertThat(values.get("value2")).isEqualTo("world");
    }

    @Test
    public void testVariables() throws Exception {
        PathParamExtractor ppe = PathParamExtractor.of("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.variables()).containsExactlyInAnyOrder("value", "value2", "value3");
    }

    @Test
    public void testSkeleton() throws Exception {
        PathParamExtractor ppe = PathParamExtractor.of("/service/{value}/test/:value2/something/{value3}");

        assertThat(ppe.skeleton()).isEqualTo("/service/{}/test/{}/something/{}");
    }

    @Test
    public void testInvalidPattern() throws Exception {
        assertThatThrownBy(() -> PathParamExtractor.of("/service/{value}/test/{value2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/service/{value}/test/{value2: Invalid PathParamExtractor");

        assertThatThrownBy(() -> PathParamExtractor.of("/service/:value/test/value2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/service/:value/test/value2}: Invalid PathParamExtractor");
    }

    @Test
    public void testEmptyPattern() throws Exception {
        assertThatThrownBy(() -> PathParamExtractor.of("/service/value/test/value2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No placeholder exists in given path: /service/value/test/value2");
    }
}
