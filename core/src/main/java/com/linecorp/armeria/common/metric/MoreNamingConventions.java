/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;

import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.PrometheusNamingConvention;

/**
 * Provides commonly-used {@link NamingConvention}s.
 */
public final class MoreNamingConventions {

    /**
     * Returns the {@link NamingConvention} that uses the user-provided names and tags as they are.
     */
    public static NamingConvention identity() {
        return (name, type, baseUnit) -> name;
    }

    /**
     * Returns the {@link NamingConvention} of <a href="http://metrics.dropwizard.io/">Dropwizard Metrics</a>.
     */
    public static NamingConvention dropwizard() {
        return identity();
    }

    /**
     * Returns the {@link NamingConvention} of <a href="https://prometheus.io/">Prometheus</a>.
     */
    public static NamingConvention prometheus() {
        return BetterPrometheusNamingConvention.INSTANCE;
    }

    /**
     * Configures all the {@link MeterRegistry}s added to the {@link Metrics#globalRegistry} to use the
     * {@link NamingConvention}s provided by this class. {@link DropwizardMeterRegistry} and
     * {@link PrometheusMeterRegistry} will be configured to use {@link #dropwizard()} and
     * {@link #prometheus()} respectively. This method is a shortcut of:
     *
     * <pre>{@code
     * configure(Metrics.globalRegistry);
     * }</pre>
     */
    public static void configure() {
        configure(Metrics.globalRegistry);
    }

    /**
     * Configures the specified {@link MeterRegistry} to use the {@link NamingConvention}s provided by this
     * class. {@link DropwizardMeterRegistry} and {@link PrometheusMeterRegistry} will be configured to use
     * {@link #dropwizard()} and {@link #prometheus()} respectively. A {@link CompositeMeterRegistry} will be
     * configured recursively.
     */
    public static void configure(MeterRegistry registry) {
        requireNonNull(registry, "registry");
        if (registry instanceof CompositeMeterRegistry) {
            ((CompositeMeterRegistry) registry).getRegistries().forEach(MoreNamingConventions::configure);
        }

        if (registryTypeMatches(registry, "io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry")) {
            registry.config().namingConvention(dropwizard());
        } else if (registryTypeMatches(registry, "io.micrometer.prometheus.PrometheusMeterRegistry")) {
            registry.config().namingConvention(prometheus());
        } else {
            // Probably OK to use the default.
        }
    }

    private static boolean registryTypeMatches(MeterRegistry registry, String typeName) {
        try {
            final Class<?> type = Class.forName(typeName, false, registry.getClass().getClassLoader());
            return type.isInstance(registry);
        } catch (ClassNotFoundException e) {
            // Can't find the registry class, most likely that registry is not an instance of
            // the type denoted by typeName.
            return false;
        }
    }

    private MoreNamingConventions() {}

    /**
     * An alternative {@link NamingConvention} of {@link PrometheusNamingConvention}.
     * <ul>
     *   <li>It handles base unit correctly regardless of the meter type.</li>
     *   <li>It converts camel-case name components to snake-case.</li>
     *   <li>It does not prepend {@code "m_"} unless it's really necessary.</li>
     * </ul>
     */
    private static final class BetterPrometheusNamingConvention implements NamingConvention {

        private static final Splitter NAME_SPLITTER = Splitter.on('.').omitEmptyStrings();

        private static final Pattern SANITIZE_PREFIX_PATTERN = Pattern.compile("^[^a-zA-Z_]");
        private static final Pattern SANITIZE_BODY_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
        private static final Pattern SANITIZE_LABEL_NAME_PREFIX_PATTERN = Pattern.compile("^[^a-zA-Z]+");
        private static final Pattern SANITIZE_LABEL_NAME_BODY_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
        private static final Pattern ALPHANUM_ONLY_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

        private static final String SUFFIX_SECONDS = "seconds";
        private static final String SUFFIX_TOTAL = "total";
        private static final String RESERVED_LABEL_NAME_PREFIX = "__";

        private static final BetterPrometheusNamingConvention INSTANCE = new BetterPrometheusNamingConvention();

        @Override
        public String name(String name, Type type, @Nullable String baseUnit) {
            final StringBuilder buf = new StringBuilder();
            for (String n : NAME_SPLITTER.split(name)) {
                if (ALPHANUM_ONLY_PATTERN.matcher(n).matches()) {
                    buf.append(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, n));
                } else {
                    buf.append(n);
                }
                buf.append('_');
            }
            buf.setLength(buf.length() - 1);

            if (type == Type.TIMER) {
                baseUnit = SUFFIX_SECONDS;
            }

            if (baseUnit != null &&
                (buf.length() < baseUnit.length() ||
                 !buf.substring(buf.length() - baseUnit.length()).equalsIgnoreCase(baseUnit))) {
                buf.append('_').append(baseUnit);
            }

            if (type == Type.COUNTER) {
                if (buf.length() < SUFFIX_TOTAL.length() ||
                    !buf.substring(buf.length() - SUFFIX_TOTAL.length()).equalsIgnoreCase(SUFFIX_TOTAL)) {
                    buf.append('_').append(SUFFIX_TOTAL);
                }
            }

            return SANITIZE_BODY_PATTERN.matcher(
                    SANITIZE_PREFIX_PATTERN.matcher(buf.toString())
                                           .replaceFirst("_")).replaceAll("_");
        }

        @Override
        public String tagKey(String key) {
            // Replaces any non-alphabet letters at the beginning with _ and
            // any non-alphanumeric letters elsewhere with _.
            final String sanitized = SANITIZE_LABEL_NAME_BODY_PATTERN.matcher(
                    SANITIZE_LABEL_NAME_PREFIX_PATTERN.matcher(key).replaceFirst("_")).replaceAll("_");

            if (sanitized.startsWith(RESERVED_LABEL_NAME_PREFIX)) {
                return 'm' + sanitized;
            }

            return sanitized;
        }
    }
}
