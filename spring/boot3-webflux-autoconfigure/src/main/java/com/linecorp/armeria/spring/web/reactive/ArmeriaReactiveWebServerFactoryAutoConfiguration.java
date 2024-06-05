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
package com.linecorp.armeria.spring.web.reactive;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.InetAddress;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.DocServiceConfigurator;
import com.linecorp.armeria.spring.HealthCheckServiceConfigurator;
import com.linecorp.armeria.spring.InternalServices;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * An {@linkplain EnableAutoConfiguration auto-configuration} for a reactive web server.
 */
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(Server.class)
@EnableConfigurationProperties({ ServerProperties.class, ArmeriaSettings.class })
@Import(DataBufferFactoryWrapperConfiguration.class)
public class ArmeriaReactiveWebServerFactoryAutoConfiguration {

    /**
     * Returns a new {@link ArmeriaReactiveWebServerFactory} bean instance.
     */
    @Bean
    @ConditionalOnMissingBean(ArmeriaReactiveWebServerFactory.class)
    public ArmeriaReactiveWebServerFactory armeriaReactiveWebServerFactory(
            ConfigurableListableBeanFactory beanFactory, Environment environment) {
        return new ArmeriaReactiveWebServerFactory(beanFactory, environment);
    }

    /**
     * Creates internal services that should not be exposed to the external network such as {@link DocService},
     * {@link PrometheusExpositionService} and {@link HealthCheckService}.
     *
     * <p>Note that if a service path is either {@code null} or empty, the associated service will not be
     * initiated. For example, {@link ArmeriaSettings#getHealthCheckPath()} is {@code null},
     * {@link HealthCheckService} will not be created automatically.
     *
     * @see ArmeriaSettings#getDocsPath()
     * @see ArmeriaSettings#getMetricsPath()
     * @see ArmeriaSettings#getHealthCheckPath()
     */
    @Bean
    public InternalServices internalServices(
            ArmeriaSettings settings,
            Optional<MeterRegistry> meterRegistry,
            ObjectProvider<HealthChecker> healthCheckers,
            ObjectProvider<HealthCheckServiceConfigurator> healthCheckServiceConfigurators,
            ObjectProvider<DocServiceConfigurator> docServiceConfigurators,
            @Value("${management.server.port:#{null}}") @Nullable Integer managementServerPort,
            @Value("${management.server.address:#{null}}") @Nullable InetAddress managementServerAddress,
            @Value("${management.server.ssl.enabled:#{false}}") boolean enableManagementServerSsl) {
        return InternalServices.of(settings, meterRegistry.orElse(Flags.meterRegistry()),
                                   healthCheckers.orderedStream().collect(toImmutableList()),
                                   healthCheckServiceConfigurators.orderedStream().collect(toImmutableList()),
                                   docServiceConfigurators.orderedStream().collect(toImmutableList()),
                                   managementServerPort, managementServerAddress, enableManagementServerSsl);
    }
}
