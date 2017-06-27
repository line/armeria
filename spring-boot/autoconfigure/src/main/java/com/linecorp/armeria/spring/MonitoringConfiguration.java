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

package com.linecorp.armeria.spring;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Map.Entry;
import java.util.Properties;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import com.codahale.metrics.logback.InstrumentedAppender;
import com.google.common.io.Resources;
import com.ryantenney.metrics.spring.config.annotation.MetricsConfigurerAdapter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

@Configuration
@ConditionalOnBean(MetricRegistry.class)
public class MonitoringConfiguration extends MetricsConfigurerAdapter {
    @Override
    public void configureReporters(MetricRegistry registry) {
        configureLogback(registry);
        configureJvm(registry);
        configureSystem(registry);
        configureGitProperties(registry);
    }

    private static void configureLogback(MetricRegistry registry) {
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME);
        InstrumentedAppender logbackMetrics = new InstrumentedAppender(registry);
        logbackMetrics.setContext(root.getLoggerContext());
        logbackMetrics.start();
        root.addAppender(logbackMetrics);
    }

    private static void configureSystem(MetricRegistry registry) {
        registry.register("system.cpu.load-average", new CpuLoadMetric());
    }

    private static void configureJvm(MetricRegistry registry) {
        registerAll("jvm.gc", new GarbageCollectorMetricSet(), registry);
        registerAll("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()),
                    registry);
        registerAll("jvm.classloader", new ClassLoadingGaugeSet(), registry);
        registerAll("jvm.memory", new MemoryUsageGaugeSet(), registry);
        registerAll("jvm.threads", new ThreadStatesGaugeSet(), registry);
    }

    private static void configureGitProperties(MetricRegistry registry) {
        Properties properties = new Properties();
        try {
            try (InputStream file = Resources.getResource("git.properties").openStream()) {
                properties.load(file);
            }
        } catch (IOException | IllegalArgumentException e) {
            // Ignore missing git.properties.
        }
        properties.forEach(
                (key, value) -> registry.register(MetricRegistry.name("git", (String) key),
                                                  (Gauge<String>) () -> (String) value));
    }

    private static void registerAll(String prefix, MetricSet metricSet, MetricRegistry registry) {
        for (Entry<String, Metric> entry : metricSet.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet) {
                registerAll(prefix + '.' + entry.getKey(), (MetricSet) entry.getValue(), registry);
            } else {
                registry.register(prefix + '.' + entry.getKey(), entry.getValue());
            }
        }
    }
}
