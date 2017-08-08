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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class MetricKeyFunctionTest {

    @Test
    public void testWithLabel() {
        final MetricKeyFunction f = log -> new MetricKey(ImmutableList.of("a"),
                                                         ImmutableMap.of("b", "c"));
        assertThat(f.withLabel("d", "e").apply(null))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", "d", "e")));
    }

    @Test
    public void testWithTypedLabel() {
        final MetricKeyFunction f = log -> new MetricKey(ImmutableList.of("a"),
                                                         ImmutableMap.of("b", "c"));
        assertThat(f.withLabel(BuiltInMetricLabel.method, "d").apply(null))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", BuiltInMetricLabel.method, "d")));
    }

    @Test
    public void testWithLabels() {
        final MetricKeyFunction f = log -> new MetricKey(ImmutableList.of("a"),
                                                         ImmutableMap.of("b", "c"));
        assertThat(f.withLabels(ImmutableMap.of("d", "e", BuiltInMetricLabel.method, "f")).apply(null))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", "d", "e", BuiltInMetricLabel.method, "f")));
    }

    @Test
    public void testWithLabelsValidation() {
        final MetricKeyFunction f = log -> new MetricKey("a");
        // Empty name.
        assertThatThrownBy(() -> f.withLabels(ImmutableMap.of("", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        // Empty typed name.
        assertThatThrownBy(() -> f.withLabels(ImmutableMap.of((MetricLabel) () -> "", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        // null typed name.
        assertThatThrownBy(() -> f.withLabels(ImmutableMap.of((MetricLabel) () -> null, "value")))
                .isInstanceOf(NullPointerException.class);
        // Unsupported key type
        assertThatThrownBy(() -> f.withLabels(ImmutableMap.of(new Object(), "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testAndThen() {
        final MetricKeyFunction f = log -> new MetricKey("foo");
        assertThat(f.andThen(k -> new MetricKey(k.nameParts().get(0) + "bar")).apply(null))
                .isEqualTo(new MetricKey("foobar"));
    }
}
