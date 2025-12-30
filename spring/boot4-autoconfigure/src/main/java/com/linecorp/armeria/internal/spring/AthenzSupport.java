/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.spring;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ResolvableType;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.AthenzConfig;

import io.micrometer.core.instrument.MeterRegistry;

final class AthenzSupport {

    static DependencyInjector injectAthenzDecorator(BeanFactory beanFactory, ServerBuilder sb,
                                                    AthenzConfig athenzConfig,
                                                    @Nullable DependencyInjector dependencyInjector,
                                                    MeterRegistry meterRegistry) {
        final URI ztsUri = athenzConfig.getZtsUri();
        final File athenzPrivateKey = athenzConfig.getAthenzPrivateKey();
        final File athenzPublicKey = athenzConfig.getAthenzPublicKey();
        final URI proxyUri = athenzConfig.getProxyUri();
        final File athenzCaCert = athenzConfig.getAthenzCaCert();
        final String oauth2KeyPath = athenzConfig.getOauth2KeysPath();
        final List<String> domains = athenzConfig.getDomains();
        final boolean jwsPolicySupport = athenzConfig.isJwsPolicySupport();
        final Duration policyRefreshInterval = athenzConfig.getPolicyRefreshInterval();
        final boolean enableMetrics = athenzConfig.isEnableMetrics();
        final String meterIdPrefix = athenzConfig.getMeterIdPrefix();

        checkState(ztsUri != null,
                   "ztsUri must be specified when athenz is enabled. " +
                   "Please set 'armeria.athenz.zts-uri' property in application properties.");
        checkState(athenzPrivateKey != null && athenzPrivateKey.exists(),
                   "athenzPrivateKey must be specified and exist when athenz is enabled. " +
                   "Please set 'armeria.athenz.athenz-private-key' property in application properties.");
        checkState(athenzPublicKey != null && athenzPublicKey.exists(),
                   "athenzPublicKey must be specified and exist when athenz is enabled. " +
                   "Please set 'armeria.athenz.athenz-public-key' property in application properties.");
        checkState(!domains.isEmpty(),
                   "domains must be specified when athenz is enabled. " +
                   "Please set 'armeria.athenz.domains' property in application properties.");

        try {
            final Class<?> factoryProvider = Class.forName(
                    "com.linecorp.armeria.internal.server.athenz.AthenzServiceDecoratorFactoryProvider");

            final Class<?> ztsBaseClientBuilder = Class.forName(
                    "com.linecorp.armeria.client.athenz.ZtsBaseClientBuilder");
            final List<Object> ztsClientCustomizers = findBeans(beanFactory, Consumer.class,
                                                                ztsBaseClientBuilder);
            final Method createMethod = factoryProvider.getMethod(
                    "create", ServerBuilder.class, URI.class, File.class, File.class,
                    URI.class, File.class, String.class, List.class, boolean.class, Duration.class,
                    MeterRegistry.class, String.class, List.class);
            final Object athenzServiceDecoratorFactory =
                    createMethod.invoke(null, sb, ztsUri, athenzPrivateKey,
                                        athenzPublicKey, proxyUri, athenzCaCert, oauth2KeyPath,
                                        domains, jwsPolicySupport, policyRefreshInterval,
                                        enableMetrics ? meterRegistry : null, meterIdPrefix,
                                        ztsClientCustomizers);
            assert "com.linecorp.armeria.server.athenz.AthenzServiceDecoratorFactory"
                    .equals(athenzServiceDecoratorFactory.getClass().getName());

            final DependencyInjector athenzInjector =
                    DependencyInjector.ofSingletons(athenzServiceDecoratorFactory);

            if (dependencyInjector == null) {
                // If no dependency injector is specified, use the reflective dependency injector for
                // backward compatibility.
                dependencyInjector = DependencyInjector.ofReflective();
            }
            dependencyInjector = athenzInjector.orElse(dependencyInjector);
            return dependencyInjector;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create AthenzServiceDecoratorFactory", e);
        }
    }

    private static List<Object> findBeans(BeanFactory beanFactory, Class<?> containerType,
                                          Class<?> genericType) {
        final ResolvableType resolvableType = ResolvableType.forClassWithGenerics(containerType, genericType);
        final ObjectProvider<Object> beanProvider = beanFactory.getBeanProvider(resolvableType);
        return beanProvider.orderedStream().collect(toImmutableList());
    }

    private AthenzSupport() {}
}
