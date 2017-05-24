/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.internal.metric;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.metric.PrometheusUtil.collectors;
import static com.linecorp.armeria.internal.metric.PrometheusUtil.registerIfAbsent;
import static com.linecorp.armeria.internal.metric.PrometheusUtil.unregister;
import static com.linecorp.armeria.internal.metric.PrometheusUtil.unregisterAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.google.common.collect.ImmutableList;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.SimpleCollector;

public class PrometheusUtilTest {

    private static final String HELP = "help";

    @Rule
    public final TestName testName = new TestName();

    private final CollectorRegistry registry = new CollectorRegistry();

    @Before
    public void clearRegistry() {
        unregisterAll(registry);
        final Map<String, Collector> map = collectors.get(registry);
        if (map != null) {
            assertThat(map).isEmpty();
        }
    }

    @Test
    public void register_normal() {
        final String name = testName.getMethodName();
        final Counter c = registerIfAbsent(registry, name, n -> Counter.build(n, HELP).create());

        c.inc();

        assertThat(collectors.get(registry)).containsEntry(name, c);
        assertThat(registry.getSampleValue(name)).isEqualTo(1.0);
    }

    @Test
    public void register_unregister_by_collector() {
        final String name = testName.getMethodName();
        final Counter c = registerIfAbsent(registry, name, n -> Counter.build(n, HELP).create());

        c.inc();

        assertThat(collectors.get(registry)).containsEntry(name, c);
        assertThat(registry.getSampleValue(name)).isEqualTo(1.0);

        assertThat(unregister(registry, c)).isTrue();

        assertThat(collectors.get(registry)).isEmpty();
        assertThat(registry.getSampleValue(name)).isNull();
    }

    @Test
    public void register_unregister_by_name() {
        final String name = testName.getMethodName();
        final Counter c = registerIfAbsent(registry, name, n -> Counter.build(n, HELP).create());

        c.inc();

        assertThat(registry.getSampleValue(name)).isEqualTo(1.0);
        assertThat(unregister(registry, name)).isTrue();
        assertThat(collectors.get(registry)).isEmpty();
        assertThat(registry.getSampleValue(name)).isNull();
    }

    @Test
    public void register_unregister_by_name_not_exists() {
        final String name = testName.getMethodName();
        final Counter c = registerIfAbsent(registry, name, n -> Counter.build(n, HELP).create());

        c.inc();

        assertThat(unregister(registry, "non_existent")).isFalse();
        assertThat(collectors.get(registry)).containsEntry(name, c);
        assertThat(registry.getSampleValue("non_existent")).isNull();
        assertThat(registry.getSampleValue(name)).isEqualTo(1.0);
    }

    @Test
    public void register_unregister_by_collector_not_exists() {
        final String name = testName.getMethodName();
        final Counter c = registerIfAbsent(registry, name, n -> Counter.build(n, HELP).create());

        c.inc();

        assertThat(unregister(registry, Counter.build("non_existent", HELP).create())).isFalse();
        assertThat(collectors.get(registry)).containsEntry(name, c);
        assertThat(registry.getSampleValue("non_existent")).isNull();
        assertThat(registry.getSampleValue(name)).isEqualTo(1.0);
    }

    @Test
    public void register_mismatching_name() {
        final String name = testName.getMethodName();
        assertThatThrownBy(() -> registerIfAbsent(registry, name,
                                                  n -> Counter.build("wrong_name", HELP).create()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void register_multiple_names() {
        final String name = testName.getMethodName();
        final String name2 = name + "_2";
        final String name3 = name + "_3";
        final Collector c = registerIfAbsent(registry, name,
                                             n -> new CollectorWithMultipleNames(name2, name, name3));

        assertThat(collectors.get(registry)).containsEntry(name, c)
                                            .containsEntry(name2, c)
                                            .containsEntry(name3, c);
    }

    private static final class CollectorWithMultipleNames extends SimpleCollector<Object> {

        private final List<String> names;

        CollectorWithMultipleNames(String... names) {
            super(new Builder() {
                {
                    name(names[0]);
                    help(HELP);
                }

                @Override
                public CollectorWithMultipleNames create() {
                    throw new UnsupportedOperationException();
                }
            });

            this.names = ImmutableList.copyOf(names);
        }

        @Override
        protected Object newChild() {
            return new Object();
        }

        @Override
        public List<MetricFamilySamples> collect() {
            return names.stream()
                        .map(name -> new MetricFamilySamples(name, Type.COUNTER, HELP, ImmutableList.of()))
                        .collect(toImmutableList());
        }
    }
}
