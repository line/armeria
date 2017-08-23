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

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.NamingConvention;
import io.prometheus.client.CollectorRegistry;

/**
 * {@link MeterRegistry} implementation for <a href="https://prometheus.io/">Prometheus</a>.
 * This implementation adds more convenient constructors and sets more sensible default {@link NamingConvention}
 * on top of the upstream implementation.
 */
@SuppressWarnings("ClassNameSameAsAncestorName")
public class PrometheusMeterRegistry extends io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry {

    /**
     * Creates a new instance with a newly-created {@link CollectorRegistry}.
     */
    public PrometheusMeterRegistry() {
        this(new CollectorRegistry());
    }

    /**
     * Creates a new instance with the specified {@link CollectorRegistry}.
     */
    public PrometheusMeterRegistry(CollectorRegistry registry) {
        this(registry, Clock.SYSTEM);
    }

    /**
     * Creates a new instance with the specified {@link CollectorRegistry}.
     */
    public PrometheusMeterRegistry(CollectorRegistry registry, Clock clock) {
        super(requireNonNull(registry, "registry"), requireNonNull(clock, "clock"));
        config().namingConvention(MoreNamingConventions.prometheus());
    }
}
