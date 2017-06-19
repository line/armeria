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

public class MetricKeyTest {

    @Test
    public void constructorValidation() {
        // Both name and labels are empty.
        assertThatThrownBy(MetricKey::new).isInstanceOf(IllegalArgumentException.class);

        // Contains an empty name part.
        assertThatThrownBy(() -> new MetricKey("a", "", "c")).isInstanceOf(IllegalArgumentException.class);

        // Contains an empty label name.
        assertThatThrownBy(() -> new MetricKey(ImmutableList.of(),
                                               ImmutableMap.of("", "value")))
                .isInstanceOf(IllegalArgumentException.class);

        // Contains an null or empty typed label name.
        assertThatThrownBy(() -> new MetricKey(ImmutableList.of(),
                                               ImmutableMap.of((MetricLabel) () -> null, "value")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MetricKey(ImmutableList.of(),
                                               ImmutableMap.of((MetricLabel) () -> "", "value")))
                .isInstanceOf(IllegalArgumentException.class);

        // Contains an unsupported key type.
        assertThatThrownBy(() -> new MetricKey(ImmutableList.of(),
                                               ImmutableMap.of(new Object(), "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testPrepend() {
        final MetricKey k = new MetricKey(ImmutableList.of("b"), ImmutableMap.of("c", "d"));
        assertThat(k.prepend("a")).isEqualTo(
                new MetricKey(ImmutableList.of("a", "b"), ImmutableMap.of("c", "d")));

        // Should not allow an empty name part.
        assertThatThrownBy(() -> k.prepend("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testAppend() {
        final MetricKey k = new MetricKey(ImmutableList.of("a"), ImmutableMap.of("c", "d"));
        assertThat(k.append("b")).isEqualTo(
                new MetricKey(ImmutableList.of("a", "b"), ImmutableMap.of("c", "d")));

        // Should not allow an empty name part.
        assertThatThrownBy(() -> k.append("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWithLabel() {
        final MetricKey k = new MetricKey(ImmutableList.of("a"),
                                          ImmutableMap.of("b", "c"));
        assertThat(k.withLabel("d", "e"))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", "d", "e")));

        // Should not allow an empty label name.
        assertThatThrownBy(() -> k.withLabel("", "d")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWithTypedLabel() {
        final MetricKey k = new MetricKey(ImmutableList.of("a"),
                                          ImmutableMap.of("b", "c"));
        assertThat(k.withLabel(BuiltInMetricLabel.method, "d"))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", BuiltInMetricLabel.method, "d")));

        // Should not allow an empty label name.
        assertThatThrownBy(() -> k.withLabel(() -> "", "d")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWithLabels() {
        final MetricKey k = new MetricKey(ImmutableList.of("a"),
                                          ImmutableMap.of("b", "c"));

        assertThat(k.withLabels(ImmutableMap.of())).isSameAs(k);

        assertThat(k.withLabels(ImmutableMap.of("d", "e", BuiltInMetricLabel.method, "f")))
                .isEqualTo(new MetricKey(ImmutableList.of("a"),
                                         ImmutableMap.of("b", "c", "d", "e", BuiltInMetricLabel.method, "f")));
    }

    @Test
    public void testWithLabelsValidation() {
        final MetricKey k = new MetricKey("a");
        // Empty name.
        assertThatThrownBy(() -> k.withLabels(ImmutableMap.of("", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        // Empty typed name.
        assertThatThrownBy(() -> k.withLabels(ImmutableMap.of((MetricLabel) () -> "", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        // null typed name.
        assertThatThrownBy(() -> k.withLabels(ImmutableMap.of((MetricLabel) () -> null, "value")))
                .isInstanceOf(NullPointerException.class);
        // Unsupported key type
        assertThatThrownBy(() -> k.withLabels(ImmutableMap.of(new Object(), "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testIncludes() {
        final MetricKey a = new MetricKey(ImmutableList.of("a", "b"),
                                          ImmutableMap.of("foo", "1"));
        final MetricKey b = new MetricKey(ImmutableList.of("a", "c"),
                                          ImmutableMap.of("foo", "1", "bar", "2"));

        assertThat(new MetricKey("a").includes(a)).isTrue();
        assertThat(new MetricKey("a").includes(b)).isTrue();
        assertThat(new MetricKey("a", "b").includes(a)).isTrue();
        assertThat(new MetricKey("a", "b").includes(b)).isFalse();
        assertThat(new MetricKey(ImmutableList.of("a"),
                                 ImmutableMap.of("foo", "1")).includes(a)).isTrue();
        assertThat(new MetricKey(ImmutableList.of("a"),
                                 ImmutableMap.of("foo", "1")).includes(b)).isTrue();
        assertThat(new MetricKey(ImmutableList.of("a"),
                                 ImmutableMap.of("bar", "2")).includes(a)).isFalse();
        assertThat(new MetricKey(ImmutableList.of("a"),
                                 ImmutableMap.of("bar", "2")).includes(b)).isTrue();

        assertThat(new MetricKey("a", "b", "c").includes(a)).isFalse();
        assertThat(new MetricKey("a", "c", "d").includes(b)).isFalse();
    }

    @Test
    public void testToString() {
        assertThat(new MetricKey("a").toString()).isEqualTo("a");
        assertThat(new MetricKey("a", "b").toString()).isEqualTo("a.b");
        assertThat(new MetricKey(ImmutableList.of("a", "b"),
                                 ImmutableMap.of("c", "d")).toString()).isEqualTo("a.b{c=d}");
        assertThat(new MetricKey(ImmutableList.of("a", "b"),
                                 ImmutableMap.of("c", "d", "e", "f")).toString()).isEqualTo("a.b{c=d,e=f}");
    }
}
