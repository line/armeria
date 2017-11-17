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

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;

/**
 * Provides the convenient factory methods for {@link DropwizardMeterRegistry} with more sensible defaults for
 * {@link NamingConvention} and {@link HierarchicalNameMapper}.
 */
public final class DropwizardMeterRegistries {

    private static final HierarchicalNameMapper DEFAULT_NAME_MAPPER = (id, convention) -> {
        final String name = id.getConventionName(convention);
        if (!id.getTags().iterator().hasNext()) {
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

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the default
     * {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry() {
        return newRegistry(DEFAULT_NAME_MAPPER);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link HierarchicalNameMapper}.
     */
    public static DropwizardMeterRegistry newRegistry(HierarchicalNameMapper nameMapper) {
        return newRegistry(nameMapper, Clock.SYSTEM);
    }

    /**
     * Returns a newly-created {@link DropwizardMeterRegistry} instance with the specified
     * {@link HierarchicalNameMapper} and {@link Clock}.
     */
    public static DropwizardMeterRegistry newRegistry(HierarchicalNameMapper nameMapper, Clock clock) {
        final DropwizardMeterRegistry meterRegistry = new DropwizardMeterRegistry(
                requireNonNull(nameMapper, "nameMapper"), requireNonNull(clock, "clock"));
        meterRegistry.config().namingConvention(MoreNamingConventions.dropwizard());
        return meterRegistry;
    }

    private DropwizardMeterRegistries() {}
}
