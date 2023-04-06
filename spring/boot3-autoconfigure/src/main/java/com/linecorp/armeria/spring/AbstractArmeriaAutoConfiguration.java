/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureServerWithArmeriaSettings;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Abstract class for implementing ArmeriaAutoConfiguration.
 */
public abstract class AbstractArmeriaAutoConfiguration {

    private static final Port DEFAULT_PORT = new Port().setPort(8080)
                                                       .setProtocol(SessionProtocol.HTTP);

    private static final String GRACEFUL_SHUTDOWN = "graceful";

    /**
     * Creates a started {@link Server} bean.
     */
    @Bean
    @ConditionalOnMissingBean(Server.class)
    public Server armeriaServer(
            ArmeriaSettings armeriaSettings,
            InternalServices internalService,
            Optional<MeterRegistry> meterRegistry,
            Optional<List<MetricCollectingServiceConfigurator>> metricCollectingServiceConfigurators,
            Optional<MeterIdPrefixFunction> meterIdPrefixFunction,
            Optional<List<ArmeriaServerConfigurator>> armeriaServerConfigurators,
            Optional<List<Consumer<ServerBuilder>>> armeriaServerBuilderConsumers,
            Optional<List<DependencyInjector>> dependencyInjectors,
            BeanFactory beanFactory) {

        if (!armeriaServerConfigurators.isPresent() &&
            !armeriaServerBuilderConsumers.isPresent()) {
            throw new IllegalStateException(
                    "No services to register, " +
                    "use ArmeriaServerConfigurator or Consumer<ServerBuilder> to configure an Armeria server.");
        }

        final ServerBuilder serverBuilder = Server.builder();

        final List<Port> ports = armeriaSettings.getPorts();
        if (ports.isEmpty()) {
            assert DEFAULT_PORT.getProtocols() != null;
            serverBuilder.port(new ServerPort(DEFAULT_PORT.getPort(), DEFAULT_PORT.getProtocols()));
        }

        configureServerWithArmeriaSettings(serverBuilder, armeriaSettings, internalService,
                                           armeriaServerConfigurators.orElse(ImmutableList.of()),
                                           armeriaServerBuilderConsumers.orElse(ImmutableList.of()),
                                           meterRegistry.orElse(Flags.meterRegistry()),
                                           meterIdPrefixFunction.orElse(
                                                   MeterIdPrefixFunction.ofDefault("armeria.server")),
                                           metricCollectingServiceConfigurators.orElse(ImmutableList.of()),
                                           dependencyInjectors.orElse(ImmutableList.of()),
                                           beanFactory);

        return serverBuilder.build();
    }

    /**
     * Wrap {@link Server} with {@link SmartLifecycle}.
     */
    @Bean
    @ConditionalOnMissingBean(ArmeriaServerSmartLifecycle.class)
    public SmartLifecycle armeriaServerGracefulShutdownLifecycle(Server server) {
        return new ArmeriaServerGracefulShutdownLifecycle(server);
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
            Optional<List<HealthChecker>> healthCheckers,
            Optional<List<HealthCheckServiceConfigurator>> healthCheckServiceConfigurators,
            Optional<List<DocServiceConfigurator>> docServiceConfigurators,
            @Value("${management.server.port:#{null}}") @Nullable Integer managementServerPort,
            @Value("${management.server.address:#{null}}") @Nullable InetAddress managementServerAddress,
            @Value("${management.server.ssl.enabled:#{false}}") boolean enableManagementServerSsl) {
        return InternalServices.of(settings, meterRegistry.orElse(Flags.meterRegistry()),
                                   healthCheckers.orElse(ImmutableList.of()),
                                   healthCheckServiceConfigurators.orElse(ImmutableList.of()),
                                   docServiceConfigurators.orElse(ImmutableList.of()),
                                   managementServerPort, managementServerAddress, enableManagementServerSsl);
    }

    /**
     * A user can configure a {@link Server} by providing an {@link ArmeriaServerConfigurator} bean.
     */
    @Bean
    @ConditionalOnProperty("server.shutdown")
    public ArmeriaServerConfigurator gracefulShutdownServerConfigurator(
            @Value("${server.shutdown}") String shutdown,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") Duration duration) {
        if (GRACEFUL_SHUTDOWN.equalsIgnoreCase(shutdown)) {
            return sb -> sb.gracefulShutdownTimeout(duration, duration);
        } else {
            return sb -> {};
        }
    }
}
