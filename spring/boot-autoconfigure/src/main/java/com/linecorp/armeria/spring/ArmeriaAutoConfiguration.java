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

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.configurePorts;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureAnnotatedServices;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureGrpcServices;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureHttpServices;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureServerWithArmeriaSettings;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureThriftServices;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Spring Boot {@link Configuration} that provides Armeria integration.
 */
@Configuration
@EnableConfigurationProperties(ArmeriaSettings.class)
@ConditionalOnMissingBean(Server.class)
public class ArmeriaAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaAutoConfiguration.class);

    private static final Port DEFAULT_PORT = new Port().setPort(8080)
                                                       .setProtocol(SessionProtocol.HTTP);

    /**
     * Create a started {@link Server} bean.
     */
    @Bean
    @Nullable
    public Server armeriaServer(
            ArmeriaSettings armeriaSettings,
            Optional<MeterRegistry> meterRegistry,
            Optional<MeterIdPrefixFunctionFactory> meterIdPrefixFunctionFactory,
            Optional<List<HealthChecker>> healthCheckers,
            Optional<List<ArmeriaServerConfigurator>> armeriaServerConfigurators,
            Optional<List<Consumer<ServerBuilder>>> armeriaServerBuilderConsumers,
            Optional<List<ThriftServiceRegistrationBean>> thriftServiceRegistrationBeans,
            Optional<List<GrpcServiceRegistrationBean>> grpcServiceRegistrationBeans,
            Optional<List<HttpServiceRegistrationBean>> httpServiceRegistrationBeans,
            Optional<List<AnnotatedServiceRegistrationBean>> annotatedServiceRegistrationBeans,
            Optional<List<DocServiceConfigurator>> docServiceConfigurators)
            throws InterruptedException {

        if (!armeriaServerConfigurators.isPresent() &&
            !armeriaServerBuilderConsumers.isPresent() &&
            !thriftServiceRegistrationBeans.isPresent() &&
            !grpcServiceRegistrationBeans.isPresent() &&
            !httpServiceRegistrationBeans.isPresent() &&
            !annotatedServiceRegistrationBeans.isPresent()) {
            // No services to register, no need to start up armeria server.
            return null;
        }

        final MeterIdPrefixFunctionFactory meterIdPrefixFuncFactory;
        if (armeriaSettings.isEnableMetrics()) {
            meterIdPrefixFuncFactory = meterIdPrefixFunctionFactory.orElse(
                    MeterIdPrefixFunctionFactory.ofDefault());
        } else {
            meterIdPrefixFuncFactory = null;
        }

        final ServerBuilder serverBuilder = Server.builder();

        final List<Port> ports = armeriaSettings.getPorts();
        if (ports.isEmpty()) {
            serverBuilder.port(new ServerPort(DEFAULT_PORT.getPort(), DEFAULT_PORT.getProtocols()));
        } else {
            configurePorts(serverBuilder, ports);
        }

        final DocServiceBuilder docServiceBuilder = DocService.builder();
        docServiceConfigurators.ifPresent(
                configurators -> configurators.forEach(
                        configurator -> configurator.configure(docServiceBuilder)));

        final String docsPath = armeriaSettings.getDocsPath();
        configureThriftServices(serverBuilder,
                                docServiceBuilder,
                                thriftServiceRegistrationBeans.orElseGet(Collections::emptyList),
                                meterIdPrefixFuncFactory,
                                docsPath);
        configureGrpcServices(serverBuilder,
                              docServiceBuilder,
                              grpcServiceRegistrationBeans.orElseGet(Collections::emptyList),
                              meterIdPrefixFuncFactory,
                              docsPath);
        configureHttpServices(serverBuilder,
                              httpServiceRegistrationBeans.orElseGet(Collections::emptyList),
                              meterIdPrefixFuncFactory);
        configureAnnotatedServices(serverBuilder,
                                   docServiceBuilder,
                                   annotatedServiceRegistrationBeans.orElseGet(Collections::emptyList),
                                   meterIdPrefixFuncFactory,
                                   docsPath);
        configureServerWithArmeriaSettings(serverBuilder, armeriaSettings,
                                           meterRegistry.orElse(Metrics.globalRegistry),
                                           healthCheckers.orElseGet(Collections::emptyList));

        armeriaServerConfigurators.ifPresent(
                configurators -> configurators.forEach(
                        configurator -> configurator.configure(serverBuilder)));

        armeriaServerBuilderConsumers.ifPresent(
                consumers -> consumers.forEach(
                        consumer -> consumer.accept(serverBuilder)));

        if (!Strings.isNullOrEmpty(docsPath)) {
            serverBuilder.serviceUnder(docsPath, docServiceBuilder.build());
        }

        final Server server = serverBuilder.build();

        server.start().handle((result, t) -> {
            if (t != null) {
                throw new IllegalStateException("Armeria server failed to start", t);
            }
            return result;
        }).join();
        logger.info("Armeria server started at ports: {}", server.activePorts());
        return server;
    }
}
