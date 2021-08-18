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

package com.linecorp.armeria.common.metric;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;

/**
 * Provides the convenient factory methods for {@link DropwizardMeterRegistry} with more sensible defaults for
 * {@link NamingConvention} and {@link HierarchicalNameMapper}.
 */
public final class DropwizardMeterRegistries {

    @VisibleForTesting
    static final HierarchicalNameMapper DEFAULT_NAME_MAPPER = (id, convention) -> {
        final String name = id.getConventionName(convention);
        if (!id.getTagsAsIterable().iterator().hasNext()) {
            return name;
        }

        // <name>.<tagName>:<tagValue>.<tagName>:<tagValue>...
        // e.g. armeria.server.requests.method:greet.service:HelloService
        final StringBuilder buf = new StringBuilder();
        buf.append(name);
        id.getConventionTags(convention).stream()
          .sorted(comparing(Tag::getKey))
          .forEach(tag -> {
              buf.append('.').append(tag.getKey());
              buf.append(':').append(tag.getValue());
          });

        return buf.toString();
    };

    @VisibleForTesting
    static final DropwizardConfig DEFAULT_DROPWIZARD_CONFIG = new DropwizardConfig() {
        @Override
        public String prefix() {
            return "dropwizard";
        }

        @Override
        @Nullable
        public String get(String k) {
            return null;
        }
    };

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the default
     * {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry() {
        return newRegistry(new MetricRegistry(), DEFAULT_NAME_MAPPER);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link MetricRegistry} and the default {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry(MetricRegistry registry) {
        return newRegistry(registry, DEFAULT_NAME_MAPPER);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry(HierarchicalNameMapper nameMapper) {
        return newRegistry(new MetricRegistry(), nameMapper, Clock.SYSTEM);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link MetricRegistry} and {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry(MetricRegistry registry,
                                                      HierarchicalNameMapper nameMapper) {
        return newRegistry(registry, nameMapper, Clock.SYSTEM);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link HierarchicalNameMapper} and {@link Clock}.
     */
    public static DropwizardMeterRegistry newRegistry(HierarchicalNameMapper nameMapper, Clock clock) {
        return newRegistry(new MetricRegistry(), nameMapper, clock);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link MetricRegistry}, {@link HierarchicalNameMapper} and {@link Clock}.
     */
    public static DropwizardMeterRegistry newRegistry(MetricRegistry registry,
                                                      HierarchicalNameMapper nameMapper,
                                                      Clock clock) {
        final DropwizardMeterRegistry meterRegistry = new DropwizardMeterRegistry(
                DEFAULT_DROPWIZARD_CONFIG,
                requireNonNull(registry, "registry"),
                requireNonNull(nameMapper, "nameMapper"),
                requireNonNull(clock, "clock")) {

            @Override
            protected Double nullGaugeValue() {
                return 0.0;
            }
        };

        return configureRegistry(meterRegistry);
    }

    /**
     * Configures the {@link DropwizardMeterRegistry} with Armeria's defaults. Useful when using a different
     * implementation of {@link DropwizardMeterRegistry}, e.g., a {@code JmxMeterRegistry}.
     *
     * @return the specified {@link DropwizardMeterRegistry}
     */
    public static <T extends DropwizardMeterRegistry> T configureRegistry(T meterRegistry) {
        requireNonNull(meterRegistry, "meterRegistry");
        meterRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getName().endsWith(".percentile") && id.getTag("phi") != null) {
                    return MeterFilterReply.DENY;
                }
                if (id.getName().endsWith(".histogram") && id.getTag("le") != null) {
                    return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        });

        return meterRegistry;
    }

    private DropwizardMeterRegistries() {}
}
