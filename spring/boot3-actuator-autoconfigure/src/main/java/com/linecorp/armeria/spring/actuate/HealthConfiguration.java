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

package com.linecorp.armeria.spring.actuate;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration.addOperationService;
import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration.corsServiceBuilder;
import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration.getExposedInternalServicePort;
import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration.obtainManagementServerPort;
import static com.linecorp.armeria.spring.actuate.WebOperationService.HAS_WEB_SERVER_NAMESPACE;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

@Configuration
@EnableConfigurationProperties(HealthEndpointProperties.class)
class HealthConfiguration {

    private static void addHealthEndpointgroups(Optional<HealthEndpointGroups> healthEndpointGroups,
                                                StatusMapperWrapper statusMapper, ServerBuilder sb,
                                                Collection<ExposableWebEndpoint> endpoints,
                                                List<Integer> exposedPorts,
                                                @Nullable CorsServiceBuilder cors) {
        healthEndpointGroups.ifPresent(groups -> {
            if (!groups.getNames().isEmpty()) {
                endpoints.stream()
                         .filter(endpoint -> endpoint.getEndpointId().equals(HealthEndpoint.ID))
                         .findFirst()
                         .ifPresent(endpoint -> addAdditionalPath(sb, exposedPorts, endpoint,
                                                                  statusMapper, cors, groups));
            }
        });
    }

    static HttpStatus statusFromHealthResult(StatusMapperWrapper statusMapper, Object result,
                                             HttpStatus fallbackStatus) {
        final HttpStatus status;
        if (result instanceof Health) {
            status = HttpStatus.valueOf(statusMapper.getStatusCode(((Health) result).getStatus()));
        } else if (result instanceof HealthComponent) {
            final Status actuatorStatus = ((HealthComponent) result).getStatus();
            status = HttpStatus.valueOf(statusMapper.getStatusCode(actuatorStatus));
        } else {
            status = fallbackStatus;
        }
        return status;
    }

    static void addAdditionalPath(ServerBuilder sb, List<Integer> exposedPorts,
                                  ExposableWebEndpoint endpoint,
                                  StatusMapperWrapper statusMapper,
                                  @Nullable CorsServiceBuilder cors, HealthEndpointGroups groups) {
        for (WebOperation operation : endpoint.getOperations()) {
            final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
            final String matchAllRemainingPathSegmentsVariable =
                    predicate.getMatchAllRemainingPathSegmentsVariable();
            // group operation has matchAllRemainingPathSegmentsVariable.
            // e.g. /actuator/health/{*path}
            // We can send a request to /actuator/health/foo if the group name is foo.
            //
            // We have to check if the group has additional path or not.
            // e.g. management:
            //        endpoint:
            //          health:
            //            group:
            //              foo:
            //                include: ping
            //                additional-path: "management:/foohealth"
            if (matchAllRemainingPathSegmentsVariable != null) {
                if (!exposedPorts.isEmpty()) {
                    final Set<HealthEndpointGroup> additionalGroups = groups.getAllWithAdditionalPath(
                            WebServerNamespace.MANAGEMENT);
                    addAdditionalPath(sb, exposedPorts, statusMapper, operation, predicate, additionalGroups,
                                      cors);
                }

                final Set<HealthEndpointGroup> additionalGroups = groups.getAllWithAdditionalPath(
                        WebServerNamespace.SERVER);
                addAdditionalPath(sb, ImmutableList.of(), statusMapper, operation, predicate, additionalGroups,
                                  cors);
            }
        }
    }

    static void addAdditionalPath(ServerBuilder sb, List<Integer> exposedPorts,
                                  StatusMapperWrapper statusMapper, WebOperation operation,
                                  WebOperationRequestPredicate predicate,
                                  Set<HealthEndpointGroup> additionalGroups,
                                  @Nullable CorsServiceBuilder cors) {
        for (HealthEndpointGroup group : additionalGroups) {
            final AdditionalHealthEndpointPath additionalPath = group.getAdditionalPath();
            if (additionalPath != null) {
                final String path = additionalPath.getValue();
                addOperationService(sb, exposedPorts, operation, statusMapper, predicate,
                                    path, ImmutableMap.of("path", new String[] { path }), cors);
            }
        }
    }

    /**
     * Returns a {@link HttpCodeStatusMapper}.
     * See <a href="https://github.com/spring-projects/spring-boot/blob/9643dbeed26268f37d37c22385704de8911b8f21/spring-boot-project/spring-boot-docs/src/docs/asciidoc/actuator/endpoints.adoc#writing-custom-healthindicators">writing-custom-healthindicators</a>.
     */
    // In case HealthEndpointAutoConfiguration is excluded
    @Bean
    @ConditionalOnMissingBean
    HttpCodeStatusMapper healthHttpCodeStatusMapper(HealthEndpointProperties properties) {
        return new SimpleHttpCodeStatusMapper(properties.getStatus().getHttpMapping());
    }

    @Bean
    @ConditionalOnMissingBean(WebEndpointsSupplier.class)
    WebEndpointDiscoverer webEndpointDiscoverer(
            ApplicationContext applicationContext,
            ParameterValueMapper parameterValueMapper,
            EndpointMediaTypes endpointMediaTypes,
            ObjectProvider<PathMapper> endpointPathMappers,
            ObjectProvider<OperationInvokerAdvisor> invokerAdvisors,
            ObjectProvider<EndpointFilter<ExposableWebEndpoint>> filters) {
        return new WebEndpointDiscoverer(applicationContext,
                                         parameterValueMapper,
                                         endpointMediaTypes,
                                         endpointPathMappers.orderedStream().collect(toImmutableList()),
                                         invokerAdvisors.orderedStream().collect(toImmutableList()),
                                         filters.orderedStream().collect(toImmutableList()));
    }

    @Bean
    StatusMapperWrapper statusMapperWrapper(HttpCodeStatusMapper statusMapper) {
        return new StatusMapperWrapper() {
            @Override
            public int getStatusCode(Object status) {
                if (!(status instanceof Status)) {
                    throw new IllegalStateException("status must be Status");
                }
                return statusMapper.getStatusCode((Status) status);
            }
        };
    }

    @Bean
    ArmeriaServerConfigurator healthConfigurator(StatusMapperWrapper statusMapper,
                                                 Optional<HealthEndpointGroups> healthEndpointGroups,
                                                 WebEndpointsSupplier endpointsSupplier,
                                                 CorsEndpointProperties corsProperties,
                                                 ManagementServerProperties serverProperties,
                                                 BeanFactory beanFactory,
                                                 ArmeriaSettings armeriaSettings) {
        return sb -> {
            final Port managementServerPort = obtainManagementServerPort(beanFactory, serverProperties);
            final Integer managementPort = managementServerPort != null ? managementServerPort.getPort() : null;
            final Integer internalServicePort = getExposedInternalServicePort(beanFactory, armeriaSettings);
            final CorsServiceBuilder cors = corsServiceBuilder(corsProperties);
            final Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
            if (HAS_WEB_SERVER_NAMESPACE) {
                final List<Integer> exposedPorts = Stream.of(managementPort, internalServicePort)
                                                         .filter(Objects::nonNull)
                                                         .collect(toImmutableList());
                // We can add additional path for health endpoint groups only when server namespace exists.
                addHealthEndpointgroups(healthEndpointGroups, statusMapper, sb, endpoints, exposedPorts, cors);
            }
        };
    }
}
