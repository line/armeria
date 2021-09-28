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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Abstract class for implementing ArmeriaAutoConfiguration of boot2-autoconfigure module
 * and ArmeriaSpringBoot1AutoConfiguration of boot1-autoconfigure module.
 */
public abstract class AbstractArmeriaAutoConfiguration {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final Port DEFAULT_PORT = new Port().setPort(8080)
                                                       .setProtocol(SessionProtocol.HTTP);

    private static final String GRACEFUL_SHUTDOWN = "graceful";

    /**
     * Create a started {@link Server} bean.
     */
    @Bean
    @ConditionalOnMissingBean(Server.class)
    public Server armeriaServer(
            ArmeriaSettings armeriaSettings,
            Optional<MeterRegistry> meterRegistry,
            Optional<List<HealthChecker>> healthCheckers,
            Optional<List<HealthCheckServiceConfigurator>> healthCheckServiceConfigurators,
            Optional<List<MetricCollectingServiceConfigurator>> metricCollectingServiceConfigurators,
            Optional<MeterIdPrefixFunction> meterIdPrefixFunction,
            Optional<List<ArmeriaServerConfigurator>> armeriaServerConfigurators,
            Optional<List<Consumer<ServerBuilder>>> armeriaServerBuilderConsumers,
            Optional<List<DocServiceConfigurator>> docServiceConfigurators) {

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

        configureServerWithArmeriaSettings(serverBuilder, armeriaSettings,
                                           armeriaServerConfigurators.orElse(ImmutableList.of()),
                                           armeriaServerBuilderConsumers.orElse(ImmutableList.of()),
                                           docServiceConfigurators.orElse(ImmutableList.of()),
                                           meterRegistry.orElse(Metrics.globalRegistry),
                                           healthCheckers.orElse(ImmutableList.of()),
                                           healthCheckServiceConfigurators.orElse(ImmutableList.of()),
                                           meterIdPrefixFunction.orElse(
                                                   MeterIdPrefixFunction.ofDefault("armeria.server")),
                                           metricCollectingServiceConfigurators.orElse(ImmutableList.of()));

        return serverBuilder.build();
    }

    /**
     * Wrap {@link Server} with {@link SmartLifecycle}.
     */
    @Bean
    public SmartLifecycle armeriaServerGracefulShutdownLifecycle(Server server) {
        return new ArmeriaServerGracefulShutdownLifecycle(server);
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
            return sb -> {
            };
        }
    }
}
