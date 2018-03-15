/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Nullable;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

public class MoreNamingConventionsTest {

    @Test
    public void configureDropwizard() {
        final MeterRegistry r = newDropwizardRegistry();
        MoreNamingConventions.configure(r);
        assertThat(r.config().namingConvention()).isSameAs(MoreNamingConventions.dropwizard());
    }

    @Test
    public void configurePrometheus() {
        final MeterRegistry r = newPrometheusRegistry();
        MoreNamingConventions.configure(r);
        assertThat(r.config().namingConvention()).isSameAs(MoreNamingConventions.prometheus());
    }

    @Test
    public void configureOthers() {
        // Unsupported registry's convention should not be affected.
        final MeterRegistry r = NoopMeterRegistry.get();
        final NamingConvention oldConvention = (name, type, baseUnit) -> "foo";
        r.config().namingConvention(oldConvention);
        MoreNamingConventions.configure(r);
        assertThat(r.config().namingConvention()).isSameAs(oldConvention);
    }

    @Test
    public void configureComposite() {
        final CompositeMeterRegistry r = new CompositeMeterRegistry();
        final NamingConvention oldConvention = (name, type, baseUnit) -> "bar";
        r.config().namingConvention(oldConvention);

        final MeterRegistry pr = newPrometheusRegistry();
        final MeterRegistry dr = newDropwizardRegistry();
        r.add(pr);
        r.add(dr);

        MoreNamingConventions.configure(r);
        assertThat(r.config().namingConvention()).isSameAs(oldConvention);
        assertThat(dr.config().namingConvention()).isSameAs(MoreNamingConventions.dropwizard());
        assertThat(pr.config().namingConvention()).isSameAs(MoreNamingConventions.prometheus());
    }

    private static DropwizardMeterRegistry newDropwizardRegistry() {
        return new DropwizardMeterRegistry(new DropwizardConfig() {
            @Override
            public String prefix() {
                return "dropwizard";
            }

            @Override
            @Nullable
            public String get(String k) {
                return null;
            }
        }, new MetricRegistry(), HierarchicalNameMapper.DEFAULT, Clock.SYSTEM) {
            @Override
            protected Double nullGaugeValue() {
                return 0.0;
            }
        };
    }

    private static PrometheusMeterRegistry newPrometheusRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
