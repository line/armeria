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

package com.linecorp.armeria.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.JvmGcMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.ProcessorMetrics;
import io.micrometer.core.instrument.binder.ThreadMetrics;

/**
 * Provides useful {@link MeterBinder}s.
 */
@Configuration
@ConditionalOnBean(MeterRegistry.class)
public class ArmeriaMeterBinders {

    // JvmMemoryMetrics and LogbackMetrics are registered automatically by @Enable*Metrics.

    /**
     * Returns {@link ClassLoaderMetrics}.
     */
    @Bean
    public ClassLoaderMetrics classLoaderMetrics() {
        return new ClassLoaderMetrics();
    }

    /**
     * Returns {@link JvmGcMetrics}.
     */
    @Bean
    public JvmGcMetrics jvmGcMetrics() {
        return new JvmGcMetrics();
    }

    /**
     * Returns {@link ProcessorMetrics}.
     */
    @Bean
    public ProcessorMetrics processorMetrics() {
        return new ProcessorMetrics();
    }

    /**
     * Returns {@link ThreadMetrics}.
     */
    @Bean
    public ThreadMetrics threadMetrics() {
        return new ThreadMetrics();
    }
}
