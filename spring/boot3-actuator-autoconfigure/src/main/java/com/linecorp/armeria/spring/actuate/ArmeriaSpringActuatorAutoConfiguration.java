/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.spring.actuate;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.configurePorts;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.maybeNewPort;
import static com.linecorp.armeria.spring.actuate.WebOperationService.HAS_WEB_SERVER_NAMESPACE;
import static com.linecorp.armeria.spring.actuate.WebOperationServiceUtil.addAdditionalPath;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.IncludeExcludeEndpointFilter;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.Link;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.InternalServiceProperties;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.InternalServiceId;

/**
 * A {@link Configuration} to enable actuator endpoints on an Armeria server. Corresponds to
 * {@link WebEndpointAutoConfiguration}.
 */
@Configuration
@AutoConfigureAfter({ EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class })
@EnableConfigurationProperties({
        WebEndpointProperties.class, CorsEndpointProperties.class, ManagementServerProperties.class,
        HealthEndpointProperties.class, ArmeriaSettings.class
})
public class ArmeriaSpringActuatorAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaSpringActuatorAutoConfiguration.class);

    @Nullable
    private static final Class<?> INTERNAL_SERVICES_CLASS;
    @Nullable
    private static final Method MANAGEMENT_SERVER_PORT_METHOD;

    static {
        Class<?> internalServicesClass = null;
        try {
            internalServicesClass =
                    Class.forName("com.linecorp.armeria.spring.InternalServices", true,
                                  ArmeriaSpringActuatorAutoConfiguration.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }
        INTERNAL_SERVICES_CLASS = internalServicesClass;

        Method managementServerPortMethod = null;
        if (INTERNAL_SERVICES_CLASS != null) {
            try {
                managementServerPortMethod = INTERNAL_SERVICES_CLASS.getMethod("managementServerPort");
            } catch (NoSuchMethodException ignored) {
                // Should never reach here.
                throw new Error();
            }
        }
        MANAGEMENT_SERVER_PORT_METHOD = managementServerPortMethod;
    }

    /**
     * Returns a {@link PathMapper}.
     * See <a href="https://github.com/spring-projects/spring-boot/blob/9643dbeed26268f37d37c22385704de8911b8f21/spring-boot-project/spring-boot-docs/src/docs/asciidoc/actuator/monitoring.adoc#customizing-the-management-endpoint-paths">customizing-the-management-endpoint-paths</a>.
     */
    // In case WebEndpointAutoConfiguration is excluded
    @Bean
    @ConditionalOnMissingBean
    PathMapper webEndpointPathMapper(WebEndpointProperties properties) {
        return new MappingWebEndpointPathMapper(properties.getPathMapping());
    }

    // In case WebEndpointAutoConfiguration is excluded
    @Bean
    @ConditionalOnMissingBean
    EndpointMediaTypes endpointMediaTypes() {
        return EndpointMediaTypes.DEFAULT;
    }

    /**
     * Returns a {@link IncludeExcludeEndpointFilter}.
     * See <a href="https://github.com/spring-projects/spring-boot/blob/9643dbeed26268f37d37c22385704de8911b8f21/spring-boot-project/spring-boot-docs/src/docs/asciidoc/actuator/endpoints.adoc#exposing-endpoints">exposing-endpoints</a>.
     */
    // In case WebEndpointAutoConfiguration is excluded
    @Bean
    @ConditionalOnMissingBean
    IncludeExcludeEndpointFilter<ExposableWebEndpoint> webExposeExcludePropertyEndpointFilter(
            WebEndpointProperties properties) {
        final WebEndpointProperties.Exposure exposure = properties.getExposure();
        return new IncludeExcludeEndpointFilter<>(ExposableWebEndpoint.class, exposure.getInclude(),
                                                  exposure.getExclude(),
                                                  EndpointExposure.WEB.getDefaultIncludes());
    }

    // In case WebEndpointAutoConfiguration is excluded
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
    ArmeriaServerConfigurator actuatorServerConfigurator(
            WebEndpointsSupplier endpointsSupplier,
            Optional<HealthEndpointGroups> healthEndpointGroups,
            EndpointMediaTypes mediaTypes,
            WebEndpointProperties properties,
            HttpCodeStatusMapper statusMapper,
            CorsEndpointProperties corsProperties,
            ConfigurableEnvironment environment,
            ManagementServerProperties serverProperties,
            BeanFactory beanFactory,
            ArmeriaSettings armeriaSettings) {
        return sb -> {
            if (serverProperties.getSsl() != null && serverProperties.getSsl().getKeyStore() != null) {
                logger.warn("Armeria doesn't support management.server.ssl using different keystore, " +
                            "it will use the keystore from armeria.ssl instead.");
            }
            final Integer managementPort = obtainManagementServerPort(sb, beanFactory, serverProperties);
            if (managementPort != null) {
                addLocalManagementPortPropertyAlias(environment, managementPort);
            }

            final Integer internalServicePort = getExposedInternalServicePort(beanFactory, armeriaSettings);
            final CorsServiceBuilder cors = corsServiceBuilder(corsProperties);
            final Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
            if (HAS_WEB_SERVER_NAMESPACE) {
                final List<Integer> exposedPorts = Stream.of(managementPort, internalServicePort)
                                                         .filter(Objects::nonNull)
                                                         .collect(toImmutableList());
                // We can add additional path for health endpoint groups only when server namespace exists.
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

            final ImmutableList.Builder<Map.Entry<EndpointMapping, Integer>> endpointMappingBuilder =
                    ImmutableList.builder();

            if (internalServicePort == null && managementPort == null) {
                endpointMappingBuilder.add(
                        Maps.immutableEntry(new EndpointMapping(properties.getBasePath()), null));
            } else {
                if (managementPort != null) {
                    endpointMappingBuilder.add(
                            Maps.immutableEntry(new EndpointMapping(serverProperties.getBasePath() +
                                                                    properties.getBasePath()),
                                                managementPort));
                }
                // If internal-services.port != management.server.port or management.server has its own address
                // We need to add actuator to internal-services port without base-path,
                if (internalServicePort != null &&
                    (!internalServicePort.equals(managementPort) ||
                     serverProperties.getAddress() != null)) {
                    endpointMappingBuilder.add(
                            Maps.immutableEntry(new EndpointMapping(properties.getBasePath()),
                                                internalServicePort));
                }
            }
            endpointMappingBuilder.build()
                                  .forEach(entry -> configureExposableWebEndpoint(sb, entry.getValue(),
                                                                                  endpoints, statusMapper,
                                                                                  mediaTypes, entry.getKey(),
                                                                                  cors, properties));

            if (cors != null) {
                sb.routeDecorator().pathPrefix("/").build(cors.newDecorator());
            }
        };
    }

    private static void configureExposableWebEndpoint(ServerBuilder sb, @Nullable Integer targetPort,
                                                      Collection<ExposableWebEndpoint> endpoints,
                                                      HttpCodeStatusMapper statusMapper,
                                                      EndpointMediaTypes mediaTypes,
                                                      EndpointMapping endpointMapping,
                                                      @Nullable CorsServiceBuilder cors,
                                                      WebEndpointProperties properties) {
        final List<Integer> ports = targetPort == null ? ImmutableList.of() : ImmutableList.of(targetPort);
        endpoints.stream()
                 .flatMap(endpoint -> endpoint.getOperations().stream())
                 .forEach(operation -> {
                     final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                     final String path = endpointMapping.createSubPath(predicate.getPath());
                     addOperationService(sb, ports, operation, statusMapper,
                                         predicate, path, ImmutableMap.of(), cors);
                 });

        if (StringUtils.hasText(endpointMapping.getPath()) && properties.getDiscovery().isEnabled()) {
            final Route route = route(
                    HttpMethod.GET.name(),
                    endpointMapping.getPath(),
                    ImmutableList.of(),
                    mediaTypes.getProduced()
            );
            final HttpService linksService = (ctx, req) -> {
                final Map<String, Link> links =
                        new EndpointLinksResolver(endpoints).resolveLinks(req.path());
                final MediaType contentType = firstNonNull(ctx.negotiatedResponseMediaType(), MediaType.JSON);
                return HttpResponse.ofJson(contentType, ImmutableMap.of("_links", links));
            };
            if (targetPort == null) {
                sb.route().addRoute(route).defaultServiceName("LinksService").build(linksService);
            } else {
                sb.virtualHost(targetPort).route().addRoute(route)
                  .defaultServiceName("LinksService")
                  .build(linksService);
            }
            if (cors != null) {
                cors.route(endpointMapping.getPath());
            }
        }
    }

    @Nullable
    private static Integer obtainManagementServerPort(ServerBuilder serverBuilder,
                                                      BeanFactory beanFactory,
                                                      ManagementServerProperties properties) {
        Object internalServices = null;
        if (MANAGEMENT_SERVER_PORT_METHOD != null) {
            internalServices = findBean(beanFactory, INTERNAL_SERVICES_CLASS);
        }

        if (internalServices == null) {
            // The management port was not configured by ArmeriaAutoConfiguration
            final Port managementPort =
                    maybeNewPort(properties.getPort(), properties.getAddress(),
                                 properties.getSsl() != null && properties.getSsl().isEnabled());
            if (managementPort == null) {
                return null;
            }
            configurePorts(serverBuilder, ImmutableList.of(managementPort));
            return managementPort.getPort();
        }

        try {
            final Port port = (Port) MANAGEMENT_SERVER_PORT_METHOD.invoke(internalServices);
            if (port == null) {
                return null;
            }
            return port.getPort();
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    @Nullable
    private static Integer getExposedInternalServicePort(BeanFactory beanFactory,
                                                         ArmeriaSettings armeriaSettings) {
        final Object internalServices = findBean(beanFactory, INTERNAL_SERVICES_CLASS);
        if (internalServices == null) {
            return null;
        }
        final InternalServiceProperties internalServiceProperties = armeriaSettings.getInternalServices();
        boolean actuatorEnabled = false;
        if (internalServiceProperties != null && internalServiceProperties.getInclude() != null) {
            actuatorEnabled = internalServiceProperties.getInclude().contains(InternalServiceId.ACTUATOR) ||
                              internalServiceProperties.getInclude().contains(InternalServiceId.ALL);
        }
        if (!actuatorEnabled) {
            return null;
        }
        return internalServiceProperties.getPort();
    }

    @Nullable
    private static <T> T findBean(BeanFactory beanFactory, Class<T> clazz) {
        try {
            return beanFactory.getBean(clazz);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("Too many " + clazz.getSimpleName() + " beans: (expected: 1)", e);
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    private static void addLocalManagementPortPropertyAlias(ConfigurableEnvironment environment, Integer port) {
        environment.getPropertySources().addLast(new PropertySource<Object>("Management Server") {

            @Override
            public Object getProperty(String name) {
                if ("local.management.port".equals(name)) {
                    return port;
                }
                return null;
            }
        });
    }

    @Nullable
    private static CorsServiceBuilder corsServiceBuilder(CorsEndpointProperties corsProperties) {
        if (corsProperties.getAllowedOrigins().isEmpty()) {
            return null;
        }

        final CorsServiceBuilder cors = CorsService.builder(corsProperties.getAllowedOrigins());
        if (!corsProperties.getAllowedMethods().contains("*")) {
            if (corsProperties.getAllowedMethods().isEmpty()) {
                cors.allowRequestMethods(HttpMethod.GET);
            } else {
                cors.allowRequestMethods(
                        corsProperties.getAllowedMethods().stream().map(HttpMethod::valueOf)::iterator);
            }
        }

        if (!corsProperties.getAllowedHeaders().isEmpty() &&
            !corsProperties.getAllowedHeaders().contains("*")) {
            cors.allowRequestHeaders(corsProperties.getAllowedHeaders());
        }

        if (!corsProperties.getExposedHeaders().isEmpty()) {
            cors.exposeHeaders(corsProperties.getExposedHeaders());
        }

        if (Boolean.TRUE.equals(corsProperties.getAllowCredentials())) {
            cors.allowCredentials();
        }

        cors.maxAge(corsProperties.getMaxAge());
        return cors;
    }

    static void addOperationService(ServerBuilder sb, List<Integer> exposedPorts,
                                    WebOperation operation, HttpCodeStatusMapper statusMapper,
                                    WebOperationRequestPredicate predicate, String path,
                                    Map<String, Object> arguments, @Nullable CorsServiceBuilder cors) {
        if (cors != null) {
            cors.route(path);
        }
        final Route route = route(predicate.getHttpMethod().name(), path,
                                  predicate.getConsumes(), predicate.getProduces());
        if (exposedPorts.isEmpty()) {
            sb.service(route, new WebOperationService(operation, statusMapper, true, arguments));
            return;
        }
        exposedPorts.forEach(port -> sb.virtualHost(port)
                                       .service(route, new WebOperationService(
                                               operation, statusMapper, false, arguments)));
    }

    private static Route route(
            String method, String path, Collection<String> consumes, Collection<String> produces) {
        return Route.builder()
                    .path(path)
                    .methods(ImmutableSet.of(HttpMethod.valueOf(method)))
                    .consumes(convertMediaTypes(consumes))
                    .produces(convertMediaTypes(produces))
                    .build();
    }

    private static Set<MediaType> convertMediaTypes(Iterable<String> mediaTypes) {
        return Streams.stream(mediaTypes).map(MediaType::parse).collect(toImmutableSet());
    }
}
