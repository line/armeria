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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.configurePorts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.http.ActuatorMediaType;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.Link;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.SocketUtils;
import org.springframework.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

/**
 * A {@link Configuration} to enable actuator endpoints on an Armeria server. Corresponds to
 * {@link WebEndpointAutoConfiguration}.
 */
@Configuration
@AutoConfigureAfter(EndpointAutoConfiguration.class)
@EnableConfigurationProperties({
        WebEndpointProperties.class, CorsEndpointProperties.class, ManagementServerProperties.class,
        ArmeriaSettings.class
})
public class ArmeriaSpringActuatorAutoConfiguration {

    @VisibleForTesting
    static final MediaType ACTUATOR_MEDIA_TYPE = MediaType.parse(ActuatorMediaType.V3_JSON);

    private static final List<String> MEDIA_TYPES =
            ImmutableList.of(ActuatorMediaType.V3_JSON, MediaTypeNames.JSON);

    @Nullable
    private static final Class<?> INTERNAL_SERVICES_CLASS;
    @Nullable
    private static final Method MANAGEMENT_SERVER_PORT_METHOD;
    @Nullable
    private static final Method INTERNAL_SERVICE_PORT_METHOD;
    @Nullable
    private static final Method ACTUATOR_ENABLED_METHOD;

    static {
        Class<?> internalServicesClass = null;
        try {
            internalServicesClass =
                    Class.forName("com.linecorp.armeria.spring.InternalServices", true,
                                  ArmeriaSpringActuatorAutoConfiguration.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }
        INTERNAL_SERVICES_CLASS = internalServicesClass;

        MANAGEMENT_SERVER_PORT_METHOD = getInvokedMethod(INTERNAL_SERVICES_CLASS, "managementServerPort");
        INTERNAL_SERVICE_PORT_METHOD = getInvokedMethod(INTERNAL_SERVICES_CLASS, "internalServicePort");
        ACTUATOR_ENABLED_METHOD = getInvokedMethod(INTERNAL_SERVICES_CLASS, "actuatorEnabled");
    }

    @Bean
    @ConditionalOnMissingBean
    EndpointMediaTypes endpointMediaTypes() {
        return new EndpointMediaTypes(MEDIA_TYPES, MEDIA_TYPES);
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
    SimpleHttpCodeStatusMapper simpleHttpCodeStatusMapper() {
        return new SimpleHttpCodeStatusMapper();
    }

    @Bean
    ArmeriaServerConfigurator actuatorServerConfigurator(
            WebEndpointsSupplier endpointsSupplier,
            EndpointMediaTypes mediaTypes,
            WebEndpointProperties properties,
            SimpleHttpCodeStatusMapper statusMapper,
            CorsEndpointProperties corsProperties,
            ConfigurableEnvironment environment,
            ManagementServerProperties serverProperties,
            BeanFactory beanFactory) {
        final EndpointMapping endpointMapping = new EndpointMapping(properties.getBasePath());

        final Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
        return sb -> {
            final Integer managementPort = obtainManagementServerPort(sb, beanFactory, serverProperties);
            if (managementPort != null) {
                addLocalManagementPortPropertyAlias(environment, managementPort);
            }
            final Integer internalServicePort = getExposedInternalServicePort(beanFactory);
            final CorsServiceBuilder cors;
            if (!corsProperties.getAllowedOrigins().isEmpty()) {
                cors = CorsService.builder(corsProperties.getAllowedOrigins());

                if (!corsProperties.getAllowedMethods().contains("*")) {
                    if (corsProperties.getAllowedMethods().isEmpty()) {
                        cors.allowRequestMethods(HttpMethod.GET);
                    } else {
                        cors.allowRequestMethods(
                                corsProperties.getAllowedMethods().stream().map(HttpMethod::valueOf)
                                        ::iterator);
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
            } else {
                cors = null;
            }

            endpoints.stream()
                     .flatMap(endpoint -> endpoint.getOperations().stream())
                     .forEach(operation -> {
                         final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                         final String path = endpointMapping.createSubPath(predicate.getPath());
                         final Route route = route(predicate.getHttpMethod().name(),
                                                   path,
                                                   predicate.getConsumes(),
                                                   predicate.getProduces());
                         if (managementPort != null) {
                             // Bind a WebOperationService to "management.server.port"
                             sb.virtualHost(managementPort)
                               .service(route, new WebOperationService(operation, statusMapper));
                         }
                         if (internalServicePort != null) {
                             sb.virtualHost(internalServicePort)
                               .service(route, new WebOperationService(operation, statusMapper));
                         }
                         if (managementPort == null && internalServicePort == null) {
                             sb.service(route, new WebOperationService(operation, statusMapper));
                         }
                         if (cors != null) {
                             cors.route(path);
                         }
                     });

            if (StringUtils.hasText(endpointMapping.getPath())) {
                final Route route = route(
                        HttpMethod.GET.name(),
                        endpointMapping.getPath(),
                        ImmutableList.of(),
                        mediaTypes.getProduced()
                );
                final HttpService linksService = (ctx, req) -> {
                    final Map<String, Link> links =
                            new EndpointLinksResolver(endpoints).resolveLinks(req.path());
                    return HttpResponse.ofJson(ACTUATOR_MEDIA_TYPE, ImmutableMap.of("_links", links));
                };
                if (managementPort != null) {
                    sb.virtualHost(managementPort)
                      .route().addRoute(route).defaultServiceName("LinksService").build(linksService);
                }
                if (internalServicePort != null) {
                    sb.virtualHost(internalServicePort)
                      .route().addRoute(route).defaultServiceName("LinksService").build(linksService);
                }
                if (managementPort == null && internalServicePort == null) {
                    sb.route().addRoute(route).defaultServiceName("LinksService").build(linksService);
                }

                if (cors != null) {
                    cors.route(endpointMapping.getPath());
                }
            }
            if (cors != null) {
                sb.routeDecorator().pathPrefix("/").build(cors.newDecorator());
            }
        };
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
            Integer port = properties.getPort();
            if (port == null || port < 0) {
                return null;
            }
            if (port == 0) {
                port = SocketUtils.findAvailableTcpPort();
            }
            // The management port was not configured by ArmeriaAutoConfiguration
            final Port managementPort = new Port().setPort(port).setProtocol(SessionProtocol.HTTP);
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
    private static Integer getExposedInternalServicePort(BeanFactory beanFactory) {
        Object internalServices = findBean(beanFactory, INTERNAL_SERVICES_CLASS);
        if (internalServices == null) {
            return null;
        }
        try {
            final boolean actuatorEnabled = (boolean) ACTUATOR_ENABLED_METHOD.invoke(internalServices);
            if (actuatorEnabled) {
                final Port internalPort = (Port) INTERNAL_SERVICE_PORT_METHOD.invoke(internalServices);
                if (internalPort != null) {
                    return internalPort.getPort();
                }
            }
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
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

    @Nullable
    private static Method getInvokedMethod(@Nullable Class<?> internalServiceClass, String methodName) {
        if (internalServiceClass == null) {
            return null;
        }
        try {
            return internalServiceClass.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            // Should never reach here.
            throw new Error();
        }
    }
}
