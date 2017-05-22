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
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;

import org.junit.Test;

import io.prometheus.client.Collector;
import io.prometheus.client.Counter;

@SuppressWarnings("unchecked")
public class PrometheusRegistryTest {
    @Test

    public void register_normal() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test", name -> Counter.build().name(name).help("test").create());

        c.inc();

        assertThat((Map<String, Collector>) registry.asMap()).containsExactly(entry("test", c));
        assertThat(registry.getSampleValue("test")).isEqualTo(1.0);
    }

    @Test
    public void register_override_method() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test", name -> Counter.build().name(name).help("test").create());

        c.inc();

        assertThat(registry.getSampleValue("test")).isEqualTo(1.0);
    }

    @Test
    public void register_unregister_by_collector() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test", name -> Counter.build().name(name).help("test").create());

        c.inc();

        assertThat((Map<String, Collector>)registry.asMap()).containsExactly(entry("test", c));
        assertThat(registry.getSampleValue("test")).isEqualTo(1.0);

        registry.unregister(c);

        assertThat((Map<String, Collector>)registry.asMap()).isEmpty();
        assertThat(registry.getSampleValue("test")).isNull();
    }

    @Test
    public void register_unregister_by_name() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test", name -> Counter.build().name(name).help("test").create());

        c.inc();

        assertThat(registry.getSampleValue("test")).isEqualTo(1.0);
        assertThat(registry.unregister("test")).isTrue();
        assertThat((Map<String, Collector>)registry.asMap()).isEmpty();
        assertThat(registry.getSampleValue("test")).isNull();
    }

    @Test
    public void register_unregister_by_name_not_exists() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test2", name -> Counter.build().name(name).help("test2").create());

        c.inc();

        assertThat(registry.unregister("test")).isFalse();
        assertThat((Map<String, Collector>) registry.asMap()).containsExactly(entry("test2", c));
        assertThat(registry.getSampleValue("test")).isNull();
        assertThat(registry.getSampleValue("test2")).isEqualTo(1.0);
    }

    @Test
    public void register_unregister_by_collector_not_exists() {
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter c = registry.register("test2", name -> Counter.build().name(name).help("test2").create());

        c.inc();
        registry.unregister(Counter.build().name("test").help("test").create());

        assertThat((Map<String, Collector>) registry.asMap()).containsExactly(entry("test2", c));
        assertThat(registry.getSampleValue("test")).isNull();
        assertThat(registry.getSampleValue("test2")).isEqualTo(1.0);
    }
}
