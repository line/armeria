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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;

/**
 * Provides useful {@link MeterBinder}s.
 */
@Configuration
@ConditionalOnClass(name = "io.micrometer.spring.autoconfigure.MetricsAutoConfiguration")
public class ArmeriaMeterBindersConfiguration {

    // JvmMemoryMetrics, LogbackMetrics, ProcessorMetrics and UptimeMetrics are registered automatically by
    // MeterBindersConfiguration.

    /**
     * Returns {@link ClassLoaderMetrics}.
     */
    @Bean
    @ConditionalOnMissingBean(ClassLoaderMetrics.class)
    public ClassLoaderMetrics classLoaderMetrics() {
        return new ClassLoaderMetrics();
    }

    /**
     * Returns {@link FileDescriptorMetrics}.
     */
    @Bean
    @ConditionalOnMissingBean(FileDescriptorMetrics.class)
    public FileDescriptorMetrics fileDescriptorMetrics() {
        return new FileDescriptorMetrics();
    }

    /**
     * Returns {@link JvmGcMetrics}.
     */
    @Bean
    @ConditionalOnMissingBean(JvmGcMetrics.class)
    public JvmGcMetrics jvmGcMetrics() {
        return new JvmGcMetrics();
    }

    /**
     * Returns {@link JvmThreadMetrics}.
     */
    @Bean
    @ConditionalOnMissingBean(JvmThreadMetrics.class)
    public JvmThreadMetrics threadMetrics() {
        return new JvmThreadMetrics();
    }
}
